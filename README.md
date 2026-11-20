# Bearis

**Bearis** es una aplicación **Android** para ejecutar modelos de lenguaje y multimodales **en el propio teléfono**: chat, descarga o importación de modelos, y un **servidor HTTP en la red local** con API **compatible con OpenAI** (`/v1/chat/completions`, `/v1/models`, streaming SSE) pensado para conectar el móvil con el PC y usar asistentes como **Continue** o scripts propios sin enviar el prompt a la nube.

La inferencia corre en el dispositivo; el tráfico con el ordenador es solo por **Wi‑Fi / LAN** cuando activas la API local (puerto habitual **8080**, según ajustes en la app).

---

## Funciones de la app (estado actual)

- **Inicio y modelos** — descarga, importación y carga de modelos soportados por el motor integrado; ajustes por modelo (incl. razonamiento / API LAN donde aplique).
- **Chat** — conversación con historial, archivos o imágenes cuando el modelo y el runtime lo permiten.
- **API local (LAN)** — `LlmApiServer` en segundo plano: expone el modelo cargado a la red; panel en la app para ver URL, estado y opciones.
- **Herramientas en la app** — entre otras: asistente de escritura, Vibe Coder, generación de imágenes, flujos tipo creador/canvas y monitor del sistema (disponibilidad concreta según build y modelo).
- **Privacidad** — el procesamiento del modelo es local; no necesitas cuentas en Bearis para usar el chat y la API en red local.

Detalle de formatos, límites y nombres exactos de modelos: `android/app/.../data/ModelData.kt` y la propia app.

---

## API local e IDEs

- Base típica del cliente: `http://<IP-del-móvil>:<puerto>/v1` (la app muestra la URL y el puerto).
- **Continue** u otros clientes OpenAI-compatibles configuran esa URL; ellos gestionan herramientas, MCP y reglas en el PC; Bearis actúa como **backend del modelo**.

Opcional: extensión de prueba en `vscode/bearis-chat/` (no hace falta para Continue).

---

## Cómo instalar (desde código)

Instalación recomendada: **compilar el proyecto** en este repositorio.

```bash
git clone https://github.com/KalciferTolueno/LLM-HUB.git
cd LLM-HUB/android
./gradlew assembleDebug
./gradlew installDebug
```

En Windows puedes usar `gradlew.bat`. Abre el proyecto en **Android Studio** si prefieres interfaz gráfica.

Pasos en el teléfono: descarga o importa un modelo, **cárgalo**, activa la **API local** si quieres usarla desde el PC.

---

## Configuración opcional de desarrollo

**Token de Hugging Face** (modelos con acceso restringido), en `android/local.properties` — **no lo subas al repositorio**:

```properties
HF_TOKEN=hf_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Sincroniza Gradle; si el build lo incluye, estará disponible vía `BuildConfig` donde corresponda.

---

## Stack (referencia rápida)

- **UI:** Kotlin, Jetpack Compose, Material 3.
- **Inferencia:** integración con los motores y rutas definidas en el proyecto (`UnifiedInferenceService`, cargadores, `gradle/libs.versions.toml`).

---

## Licencia

Consulta el archivo **`LICENSE`** en la raíz del repositorio.

---

## Reconocimiento (origen del código)

Bearis **partió como fork** del proyecto **[LLM-Hub](https://github.com/timmyy123/LLM-Hub)** ([@timmyy123](https://github.com/timmyy123)). Este repositorio (**[KalciferTolueno/LLM-HUB](https://github.com/KalciferTolueno/LLM-HUB)**) es el desarrollo actual de **Bearis**: **no está enlazado para sincronizarse automáticamente** con el proyecto original; las funciones y la API pueden diferir. Si reutilizas el código, respeta la licencia del proyecto original y la atribución que exija.

---

## Repositorio

**GitHub:** [github.com/KalciferTolueno/LLM-HUB](https://github.com/KalciferTolueno/LLM-HUB) · nombre del producto en pantalla: **Bearis**
