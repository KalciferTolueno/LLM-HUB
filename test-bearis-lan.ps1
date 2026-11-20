<#
.SYNOPSIS
  Prueba rápida del LAN API de Bearis (OpenAI-compatible /v1/chat/completions).

.DESCRIPTION
  - Petición 1: chat simple sin tools.
  - Petición 2: misma URL con tools + tool_choice (como Continue/Ollama).

  Antes de ejecutar: en el móvil, modelo cargado + Local API encendido; PC y móvil en la misma Wi‑Fi.

.PARAMETER BearisUrl
  URL completa del endpoint, por defecto http://<IP>:8080/v1/chat/completions

.PARAMETER TimeoutSec
  Tiempo máximo por petición (inferencia en el teléfono puede tardar varios minutos).

.EXAMPLE
  .\test-bearis-lan.ps1
.EXAMPLE
  .\test-bearis-lan.ps1 -BearisUrl "http://192.168.1.42:8080/v1/chat/completions" -TimeoutSec 900
#>
param(
  [string]$BearisUrl = "http://192.168.0.2:8080/v1/chat/completions",
  [int]$TimeoutSec = 600
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-BearisPost([string]$Uri, [string]$JsonBody, [int]$TimeoutSeconds) {
  Add-Type -AssemblyName System.Net.Http -ErrorAction Stop
  $handler = New-Object System.Net.Http.HttpClientHandler
  $client = New-Object System.Net.Http.HttpClient($handler)
  $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
  try {
    $content = [System.Net.Http.StringContent]::new(
      $JsonBody,
      [System.Text.UTF8Encoding]::new($false),
      "application/json"
    )
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, $Uri)
    $req.Content = $content
    [void]$req.Headers.TryAddWithoutValidation("Authorization", "Bearer bearis")
    $resp = $client.SendAsync($req).GetAwaiter().GetResult()
    try {
      $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
      if (-not $resp.IsSuccessStatusCode) {
        $snippet = if ($text.Length -le 800) { $text } else { $text.Substring(0, 800) }
        throw "HTTP $([int]$resp.StatusCode): $snippet"
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

function Show-Choice([object]$j) {
  if ($null -eq $j.choices -or $j.choices.Count -lt 1) {
    Write-Host "(sin choices)"
    return
  }
  $c0 = $j.choices[0]
  Write-Host "finish_reason:" $c0.finish_reason
  $msg = $c0.message
  if ($null -ne $msg.content) {
    Write-Host "content:" $msg.content
  }
  if ($null -ne $msg.tool_calls -and $msg.tool_calls.Count -gt 0) {
    Write-Host "tool_calls:" ($msg.tool_calls | ConvertTo-Json -Depth 8 -Compress)
  }
}

$uri = $BearisUrl.Trim()
Write-Host "URL: $uri"
Write-Host "Timeout por petición: ${TimeoutSec}s"
Write-Host ""

$body1 = @'
{
  "model": "BEARIS",
  "stream": false,
  "temperature": 0.3,
  "max_tokens": 200,
  "messages": [
    { "role": "system", "content": "Responde en una sola frase corta en español." },
    { "role": "user", "content": "Saluda y di que eres Bearis en el teléfono." }
  ]
}
'@

Write-Host "=== 1) Chat simple (sin tools) ==="
$t1 = Invoke-BearisPost -Uri $uri -JsonBody $body1 -TimeoutSeconds $TimeoutSec
$j1 = $t1 | ConvertFrom-Json
Show-Choice $j1

$body2 = @'
{
  "model": "BEARIS",
  "stream": false,
  "temperature": 0.2,
  "max_tokens": 400,
  "tool_choice": "auto",
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "create_new_file",
        "description": "Crear o sobrescribir un archivo.",
        "parameters": {
          "type": "object",
          "properties": {
            "filepath": { "type": "string" },
            "contents": { "type": "string" }
          },
          "required": ["filepath", "contents"]
        }
      }
    }
  ],
  "messages": [
    { "role": "system", "content": "Si piden un archivo, usa create_new_file con JSON válido. Si solo saludan, responde en texto." },
    { "role": "user", "content": "Crea prueba-ps1.txt con una sola línea: ok-bearis" }
  ]
}
'@

Write-Host ""
Write-Host "=== 2) Con tools + tool_choice ==="
$t2 = Invoke-BearisPost -Uri $uri -JsonBody $body2 -TimeoutSeconds $TimeoutSec
$j2 = $t2 | ConvertFrom-Json
Show-Choice $j2

Write-Host ""
Write-Host "Listo."
