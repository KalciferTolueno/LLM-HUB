param(
  [string]$BearisUrl = "http://192.168.0.2:8080/v1/chat/completions",
  [string]$OllamaUrl = "http://127.0.0.1:11434/v1/chat/completions",
  [string]$BearisModel = "local",
  [string]$OllamaModel = "gemma4:e4b",
  # Si lo indicas, se usa ESTE JSON para ambos servidores (misma peticion); solo se cambia "model" por destino.
  [string]$RequestJson = "",
  # Sin -RequestJson, el script arma el body con estos textos (por defecto: prueba minima, sin pedir HTML).
  [string]$Prompt = "Responde en una sola frase corta.",
  [string]$SystemPrompt = "Eres un asistente breve y directo.",
  [string]$OutputDir = ".\\compare-out",
  [int]$MaxTokens = 400,
  [double]$Temperature = 0.2,
  [double]$TopP = 0.9,
  # Bearis en el móvil puede tardar mucho (carga de modelo + inferencia). Invoke-WebRequest en PS 5.1 no expira solo.
  [int]$TimeoutSec = 600,
  # Cuando Ollama no está en esta máquina (p. ej. agente remoto) o no quieres llamarlo.
  [switch]$SkipOllama
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Ensure-Dir([string]$PathValue) {
  if (-not (Test-Path -LiteralPath $PathValue)) {
    New-Item -ItemType Directory -Path $PathValue | Out-Null
  }
}

function Save-Json([string]$PathValue, [object]$Data) {
  $json = $Data | ConvertTo-Json -Depth 50
  # Ollama (y otros) rechazan el cuerpo si el JSON empieza con BOM; Set-Content -Encoding UTF8 en PS5 pone BOM.
  $utf8NoBom = New-Object System.Text.UTF8Encoding $false
  [System.IO.File]::WriteAllText($PathValue, $json, $utf8NoBom)
}

function Write-Utf8NoBom([string]$PathValue, [string]$Text) {
  $enc = New-Object System.Text.UTF8Encoding $false
  [System.IO.File]::WriteAllText($PathValue, $Text, $enc)
}

# Con Set-StrictMode, leer propiedades inexistentes en PSCustomObject (p. ej. Bearis sin "reasoning") falla.
function Get-DynamicProp([object]$Obj, [string]$Name) {
  if ($null -eq $Obj) { return $null }
  $p = $Obj.PSObject.Properties[$Name]
  if ($null -eq $p) { return $null }
  return $p.Value
}

function Extract-AssistantText([object]$ResponseObj) {
  if ($null -eq $ResponseObj) { return "" }
  if ($null -eq $ResponseObj.choices) { return "" }
  if ($ResponseObj.choices.Count -lt 1) { return "" }
  $first = $ResponseObj.choices[0]
  if ($null -eq $first.message) { return "" }
  $msg = $first.message
  $parts = New-Object System.Collections.ArrayList
  $reasoning = Get-DynamicProp $msg "reasoning"
  if ($null -ne $reasoning -and "$reasoning".Trim().Length -gt 0) {
    [void]$parts.Add("[reasoning] " + [string]$reasoning)
  }
  $content = Get-DynamicProp $msg "content"
  if ($null -ne $content -and "$content".Length -gt 0) {
    [void]$parts.Add([string]$content)
  }
  $toolCalls = Get-DynamicProp $msg "tool_calls"
  if ($null -ne $toolCalls) {
    $tcArr = @($toolCalls)
    if ($tcArr.Count -gt 0) {
      [void]$parts.Add("[tool_calls] " + ($tcArr | ConvertTo-Json -Depth 12 -Compress))
    }
  }
  if ($parts.Count -eq 0) { return "" }
  return [string]::Join("`n", @($parts.ToArray()))
}

function Extract-SseText([string]$PathValue) {
  if (-not (Test-Path -LiteralPath $PathValue)) { return "" }
  # Sin UTF8, acentos en SSE (p. ej. Bearis) se leen mal: responderÃ© en vez de responderé
  $raw = Get-Content -LiteralPath $PathValue -Raw -Encoding utf8
  $builder = New-Object System.Text.StringBuilder
  $lines = $raw -split "`r?`n"
  foreach ($line in $lines) {
    $trimmed = $line.Trim()
    if (-not $trimmed.StartsWith("data:")) { continue }
    $data = $trimmed.Substring(5).Trim()
    if ($data -eq "[DONE]") { continue }
    try {
      $obj = $data | ConvertFrom-Json
      if ($null -ne $obj.choices -and $obj.choices.Count -gt 0) {
        $delta = $obj.choices[0].delta
        if ($null -eq $delta) { continue }
        # OpenAI: assistant text. Ollama Gemma: often empty content + delta.reasoning + delta.tool_calls.
        $dContent = Get-DynamicProp $delta "content"
        if ($null -ne $dContent -and "$dContent".Length -gt 0) {
          [void]$builder.Append([string]$dContent)
        }
        $dReasoning = Get-DynamicProp $delta "reasoning"
        if ($null -ne $dReasoning -and "$dReasoning".Length -gt 0) {
          [void]$builder.Append([string]$dReasoning)
        }
        $dToolCalls = Get-DynamicProp $delta "tool_calls"
        if ($null -ne $dToolCalls) {
          $tcArr = @($dToolCalls)
          if ($tcArr.Count -gt 0) {
            if ($builder.Length -gt 0) { [void]$builder.Append("`n") }
            [void]$builder.Append("[tool_calls] " + ($tcArr | ConvertTo-Json -Depth 12 -Compress))
          }
        }
      }
    } catch {
      # Ignore malformed chunks to keep comparison running.
    }
  }
  if ($builder.Length -eq 0) {
    $t = $raw.TrimStart()
    if ($t.StartsWith("{")) {
      try {
        $err = $raw | ConvertFrom-Json
        if ($null -ne $err.error.message) {
          return "[stream no SSE / error] " + [string]$err.error.message
        }
      } catch { }
    }
  }
  return $builder.ToString()
}

function Invoke-HttpPostJson([string]$Uri, [string]$JsonBody, [int]$TimeoutSeconds) {
  Add-Type -AssemblyName System.Net.Http -ErrorAction Stop
  $handler = New-Object System.Net.Http.HttpClientHandler
  $client = New-Object System.Net.Http.HttpClient($handler)
  $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
  $req = $null
  try {
    $content = [System.Net.Http.StringContent]::new(
      $JsonBody,
      [System.Text.UTF8Encoding]::new($false),
      "application/json"
    )
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, $Uri)
    $req.Content = $content
    [void]$req.Headers.TryAddWithoutValidation("Authorization", "Bearer bearis")
    $resp = $null
    try {
      $resp = $client.SendAsync($req).GetAwaiter().GetResult()
    } catch {
      $detail = $_.Exception.Message
      $x = $_.Exception.InnerException
      while ($null -ne $x) {
        $detail += " | " + $x.Message
        $x = $x.InnerException
      }
      throw "No se pudo conectar a ${Uri} (${detail}). Si es Ollama: abre la app 'Ollama' en Windows o ejecuta `ollama serve`, y comprueba con: curl.exe http://127.0.0.1:11434/api/tags"
    }
    try {
      $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
      if (-not $resp.IsSuccessStatusCode) {
        $snippet = if ($text.Length -le 500) { $text } else { $text.Substring(0, 500) }
        throw "HTTP $([int]$resp.StatusCode) from ${Uri}: $snippet"
      }
      return $text
    } finally {
      if ($null -ne $resp) { $resp.Dispose() }
    }
  } finally {
    if ($null -ne $req) { $req.Dispose() }
    if ($null -ne $client) { $client.Dispose() }
    if ($null -ne $handler) { $handler.Dispose() }
  }
}

function Print-Section([string]$Title, [string]$Content) {
  Write-Host ""
  Write-Host "=== $Title ==="
  if ([string]::IsNullOrWhiteSpace($Content)) {
    Write-Host "(empty)"
  } else {
    Write-Host $Content
  }
}

$resolvedOutput = Resolve-Path -Path "." | Select-Object -ExpandProperty Path
$outputPath = Join-Path $resolvedOutput $OutputDir
Ensure-Dir $outputPath

$messages = @(
  @{
    role = "system"
    content = $SystemPrompt
  },
  @{
    role = "user"
    content = $Prompt
  }
)

$payload = @{
  model = $BearisModel
  stream = $false
  temperature = $Temperature
  top_p = $TopP
  max_tokens = $MaxTokens
  messages = $messages
}

$payloadStream = @{
  model = $BearisModel
  stream = $true
  temperature = $Temperature
  top_p = $TopP
  max_tokens = $MaxTokens
  messages = $messages
}

$payloadPath = Join-Path $outputPath "payload.json"
$payloadStreamPath = Join-Path $outputPath "payload-stream.json"

if (-not [string]::IsNullOrWhiteSpace($RequestJson)) {
  $reqResolved = Resolve-Path -LiteralPath $RequestJson -ErrorAction Stop
  $basePayload = Get-Content -LiteralPath $reqResolved -Raw -Encoding utf8 | ConvertFrom-Json
  $streamVal = $false
  if ($null -ne $basePayload.stream) { $streamVal = [bool]$basePayload.stream }
  $nonStream = $basePayload | ConvertTo-Json -Depth 50 | ConvertFrom-Json
  $nonStream.stream = $false
  $withStream = $basePayload | ConvertTo-Json -Depth 50 | ConvertFrom-Json
  $withStream.stream = $true
  Save-Json -PathValue $payloadPath -Data $nonStream
  Save-Json -PathValue $payloadStreamPath -Data $withStream
  Write-Host "Using shared request file: $reqResolved"
} else {
  Save-Json -PathValue $payloadPath -Data $payload
  Save-Json -PathValue $payloadStreamPath -Data $payloadStream
}

Write-Host "Output folder: $outputPath"
Write-Host "Payload: $payloadPath"
Write-Host "Bearis model: $BearisModel"
Write-Host "Ollama model: $OllamaModel"
if ([string]::IsNullOrWhiteSpace($RequestJson)) {
  Write-Host "Mensaje de prueba (usa -Prompt / -SystemPrompt o -RequestJson para cambiarlo):"
  Write-Host "  system: $SystemPrompt"
  Write-Host "  user:   $Prompt"
}

$headers = @{
  "Content-Type" = "application/json"
  "Authorization" = "Bearer bearis"
}

$ollamaRespPath = Join-Path $outputPath "ollama-response.json"
$bearisRespPath = Join-Path $outputPath "bearis-response.json"
$ollamaRawPath = Join-Path $outputPath "ollama-response-RAW.txt"
$bearisRawPath = Join-Path $outputPath "bearis-response-RAW.txt"

Write-Host ""
Write-Host "Timeout por peticion HTTP: ${TimeoutSec}s (param -TimeoutSec para cambiar)."
Write-Host "Si Bearis se queda aqui, revisa: API encendida en el movil, misma WiFi, firewall, modelo ya cargado."
if ($SkipOllama) {
  Write-Host "Modo: -SkipOllama (solo Bearis + archivos Bearis)."
}
Write-Host ""
if (-not $SkipOllama) {
  Write-Host "Calling Ollama non-stream..."
  $ollamaPayload = Get-Content -LiteralPath $payloadPath -Raw -Encoding utf8 | ConvertFrom-Json
  $ollamaPayload.model = $OllamaModel
  $ollamaBody = $ollamaPayload | ConvertTo-Json -Depth 50
  $ollamaContent = Invoke-HttpPostJson -Uri $OllamaUrl -JsonBody $ollamaBody -TimeoutSeconds $TimeoutSec
  Write-Utf8NoBom -PathValue $ollamaRawPath -Text $ollamaContent
  $ollamaResp = $ollamaContent | ConvertFrom-Json
  Save-Json -PathValue $ollamaRespPath -Data $ollamaResp
} else {
  Write-Host "Skipping Ollama non-stream."
  $ollamaResp = $null
  Write-Utf8NoBom -PathValue $ollamaRawPath -Text "(skipped -SkipOllama)"
}

Write-Host "Calling Bearis non-stream..."
$bearisBody = Get-Content -LiteralPath $payloadPath -Raw -Encoding utf8
$bearisPayloadObj = $bearisBody | ConvertFrom-Json
$bearisPayloadObj.model = $BearisModel
$bearisBodyResolved = $bearisPayloadObj | ConvertTo-Json -Depth 50
$bearisContent = Invoke-HttpPostJson -Uri $BearisUrl -JsonBody $bearisBodyResolved -TimeoutSeconds $TimeoutSec
Write-Utf8NoBom -PathValue $bearisRawPath -Text $bearisContent
$bearisResp = $bearisContent | ConvertFrom-Json
Save-Json -PathValue $bearisRespPath -Data $bearisResp

$ollamaText = if ($null -eq $ollamaResp) { "" } else { Extract-AssistantText -ResponseObj $ollamaResp }
$bearisText = Extract-AssistantText -ResponseObj $bearisResp

Print-Section -Title "OLLAMA (non-stream)" -Content $(if ($SkipOllama) { "(skipped)" } else { $ollamaText })
Print-Section -Title "BEARIS (non-stream)" -Content $bearisText

if (-not $SkipOllama) {
  if ($ollamaText -eq $bearisText) {
    Write-Host ""
    Write-Host "Non-stream result: EXACT MATCH"
  } else {
    Write-Host ""
    Write-Host "Non-stream result: DIFFERENT"
    Write-Host "Ollama chars: $($ollamaText.Length)"
    Write-Host "Bearis chars: $($bearisText.Length)"
  }
}

$ollamaSsePath = Join-Path $outputPath "ollama-sse.txt"
$bearisSsePath = Join-Path $outputPath "bearis-sse.txt"

Write-Host ""
if (-not $SkipOllama) {
  Write-Host "Calling Ollama stream..."
  $ollamaPayloadStream = Get-Content -LiteralPath $payloadStreamPath -Raw -Encoding utf8 | ConvertFrom-Json
  $ollamaPayloadStream.model = $OllamaModel
  $ollamaPayloadStreamPath = Join-Path $outputPath "payload-stream-ollama.json"
  Save-Json -PathValue $ollamaPayloadStreamPath -Data $ollamaPayloadStream

  & curl.exe -s -N -m $TimeoutSec $OllamaUrl `
    -H "Content-Type: application/json" `
    -H "Authorization: Bearer bearis" `
    --data-binary "@$ollamaPayloadStreamPath" `
    -o $ollamaSsePath
  if ($LASTEXITCODE -ne 0) { throw "curl Ollama stream failed (exit $LASTEXITCODE)" }
} else {
  Write-Host "Skipping Ollama stream."
  Write-Utf8NoBom -PathValue $ollamaSsePath -Text ""
}

Write-Host "Calling Bearis stream..."
$bearisPayloadStream = Get-Content -LiteralPath $payloadStreamPath -Raw -Encoding utf8 | ConvertFrom-Json
$bearisPayloadStream.model = $BearisModel
$bearisPayloadStreamPath = Join-Path $outputPath "payload-stream-bearis.json"
Save-Json -PathValue $bearisPayloadStreamPath -Data $bearisPayloadStream

& curl.exe -s -N -m $TimeoutSec $BearisUrl `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer bearis" `
  --data-binary "@$bearisPayloadStreamPath" `
  -o $bearisSsePath
if ($LASTEXITCODE -ne 0) { throw "curl Bearis stream failed (exit $LASTEXITCODE)" }

$ollamaStreamText = if ($SkipOllama) { "" } else { Extract-SseText -PathValue $ollamaSsePath }
$bearisStreamText = Extract-SseText -PathValue $bearisSsePath

Print-Section -Title "OLLAMA (stream parsed)" -Content $(if ($SkipOllama) { "(skipped)" } else { $ollamaStreamText })
Print-Section -Title "BEARIS (stream parsed)" -Content $bearisStreamText

if (-not $SkipOllama) {
  if ($ollamaStreamText -eq $bearisStreamText) {
    Write-Host ""
    Write-Host "Stream result: EXACT MATCH"
  } else {
    Write-Host ""
    Write-Host "Stream result: DIFFERENT"
    Write-Host "Ollama stream chars: $($ollamaStreamText.Length)"
    Write-Host "Bearis stream chars: $($bearisStreamText.Length)"
  }
}

Write-Host ""
Write-Host "Files generated:"
Write-Host " - $payloadPath"
Write-Host " - $payloadStreamPath"
if (-not $SkipOllama) {
  Write-Host " - $ollamaPayloadStreamPath"
}
Write-Host " - $bearisPayloadStreamPath"
Write-Host " - $ollamaRawPath   (literal HTTP body, non-stream)"
Write-Host " - $bearisRawPath   (literal HTTP body, non-stream)"
Write-Host " - $ollamaRespPath"
Write-Host " - $bearisRespPath"
Write-Host " - $ollamaSsePath   (raw SSE, stream)"
Write-Host " - $bearisSsePath   (raw SSE, stream)"
Write-Host ""
Write-Host "Diff respuestas crudas (VS Code):"
Write-Host "  code --diff `"$ollamaRawPath`" `"$bearisRawPath`""
Write-Host "Diff SSE:"
Write-Host "  code --diff `"$ollamaSsePath`" `"$bearisSsePath`""
Write-Host ""
Write-Host "Mismo JSON de entrada (ejemplo):"
Write-Host "  .\compare-ollama-vs-bearis.ps1 -RequestJson .\mi-request.json -BearisUrl http://TU_MOVIL:8080/v1/chat/completions"
