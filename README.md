# ShaderPlayerAndroid

Android-аналог Win32 ShaderPlayer, построенный на Kotlin, Kotlin Coroutines и
Jetpack Compose. Рендер выполняется через OpenGL ES 3.0 внутри `GLSurfaceView`,
встроенного в Compose с помощью `AndroidView`.

## Реализовано

- открытие `*.frag`, `*.glsl`, `*.txt`, `*.c` и других текстовых файлов через SAF;
- ShaderToy `mainImage(out vec4, in vec2)`;
- обычный GLSL ES fragment shader с `main()`;
- автоматическая адаптация desktop GLSL к `#version 300 es`;
- `iResolution`, `iTime`, `iTimeDelta`, `iFrameRate`, `iFrame`, `iMouse`, `iDate`;
- Bonzomatic uniforms `fGlobalTime` и `v2Resolution`;
- `iChannel0..3`, `iChannelResolution[4]`, `iChannelTime[4]`;
- выбор четырёх локальных текстур через Storage Access Framework;
- пауза, перезагрузка, сброс времени и камеры;
- FPS и среднее время кадра;
- сохраняемые настройки через DataStore;
- русская и английская локализация;
- orbit/pan/zoom touch-управление;
- возможность полностью отключить управление сцены плеера, оставив `iMouse`
  шейдеру;
- специальный slider для `iSphereOffset`, если uniform присутствует в исходнике.

## Touch-управление

При включённой настройке **«Управление сценой плеера»**:

- один палец — orbit;
- два пальца — pan;
- pinch — zoom;
- пункт **«Сбросить камеру»** возвращает исходное состояние.

Адаптер пытается подключить настоящий orbit к распространённой паре
`cameraPosition` / `cameraTarget` и к функциям `getStaticCameraRay`,
`getCameraRay`, `buildCameraRay`, `cameraRay`. Если камера не распознана,
ShaderToy получает преобразованный `fragCoord`, то есть управление остаётся 2D.

При отключённой настройке исходные координаты и `iMouse` передаются без
вмешательства плеера. Это режим для шейдеров с собственным управлением.

## Ограничения относительно Windows-версии

Android не предоставляет Direct3D 12 и DXC runtime, поэтому в этом проекте нет
HLSL backend. Для HLSL-подобного workflow на Android нужен отдельный Vulkan
backend с компиляцией HLSL/GLSL в SPIR-V. Это следующий самостоятельный этап, а
не безопасная часть OpenGL ES MVP.

Также пока не реализованы:

- ShaderToy Buffer A-D;
- cubemap/video channels;
- редактор исходника внутри приложения;
- GPU timestamp queries на всех драйверах;
- надёжный автоматический patch произвольной пользовательской камеры.

## Архитектура

```text
Compose UI
   │
ShaderPlayerViewModel ── Coroutines / StateFlow / DataStore / SAF
   │
ShaderRenderController ── Channel<RenderCommand>
   │
ShaderGLSurfaceView ── touch + lifecycle
   │
ShaderRenderer ── OpenGL ES 3.0
   │
ShaderSourceAdapter ── ShaderToy/desktop GLSL → GLSL ES 3.00
```

OpenGL-команды выполняются только в GL thread через `queueEvent`. Чтение файлов и
декодирование текстур выполняются на `Dispatchers.IO`.

## Сборка

Требования:

- Android Studio с JDK 17;
- Android SDK 36;
- Gradle 8.13;
- устройство с OpenGL ES 3.0.

Открой корень проекта в Android Studio и выполни `app`.

Бинарный `gradle-wrapper.jar` не включён в архив. Перед первой командной
сборкой запусти:

```text
init_gradle_wrapper.bat
```

или на Linux/macOS:

```bash
./init_gradle_wrapper.sh
```

Скрипт скачивает wrapper из официального репозитория Gradle и проверяет SHA-256.
После этого работают обычные команды `gradlew.bat assembleDebug` и
`./gradlew assembleDebug`. Android Studio также может восстановить wrapper
самостоятельно.

## Версии

- Android Gradle Plugin 8.13.2;
- Kotlin 2.3.10;
- Compose BOM 2026.06.00;
- Activity Compose 1.13.0;
- Lifecycle 2.10.0;
- DataStore 1.2.1.

## Настройки производительности

В меню настроек добавлены:

- OpenGL ES (native driver) и Vulkan через ANGLE;
- внутренний render scale 50–100%;
- динамическое разрешение с минимальным масштабом;
- целевые 30/60/90/120 FPS через `Surface.setFrameRate`;
- Linear/Nearest upscale;
- профили количества ray-marching шагов;
- highp/mediump precision;
- расширенная статистика FPS, GPU time, фактического backend и внутреннего разрешения.

На Android 15 и новее Vulkan-режим сохраняет текущую GLES/ShaderToy-совместимость и переключает системный
GLES-драйвер приложения на ANGLE. Android разрешает менять эту настройку только
отладочному приложению с `WRITE_SECURE_SETTINGS`. Один раз выполните:

```bat
adb shell pm grant com.goodwin.shaderplayer android.permission.WRITE_SECURE_SETTINGS
```

После этого выберите backend в приложении. Плеер автоматически перезапустится.
Строка статистики показывает фактически активный backend, поэтому можно сразу
проверить, был ли загружен ANGLE/Vulkan.
