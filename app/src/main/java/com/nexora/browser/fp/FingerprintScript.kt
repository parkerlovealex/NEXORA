package com.nexora.browser.fp

import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DevicePreset
import org.json.JSONObject

/**
 * Builds the profile-scoped document-start privacy script.
 *
 * The script applies deterministic, low-amplitude transformations only to the
 * browser APIs selected in the profile. It does not claim to make a WebView
 * indistinguishable from a native browser. Worker globals are separate from
 * document globals and are measured by the bundled inspector rather than
 * silently reported as protected.
 */
object FingerprintScript {

    fun build(profile: BrowserProfile, preset: DevicePreset): String {
        val seed = effectiveSeed(profile)
        val cssW = preset.cssWidth
        val cssH = preset.cssHeight
        val deviceMemory = deviceMemoryBucket(profile.ramGb)
        val platform = if (preset.isIos) "iPhone" else "Linux armv8l"
        val language = profile.language.ifEmpty { "en-US" }
        val vendor = webglVendor(preset)
        val renderer = preset.gpu

        val sb = StringBuilder()
        sb.append("(function(){\n'use strict';\n")
        sb.append(prngSource())
        sb.append(
            """
            function __nxFindDescriptor(obj, prop) {
              try {
                for (var cursor = obj; cursor; cursor = Object.getPrototypeOf(cursor)) {
                  var descriptor = Object.getOwnPropertyDescriptor(cursor, prop);
                  if (descriptor) return descriptor;
                }
              } catch (e) {}
              return null;
            }
            function __nxDef(obj, prop, val) {
              try {
                var descriptor = __nxFindDescriptor(obj, prop);
                Object.defineProperty(obj, prop, {
                  get: function() { return val; },
                  configurable: true,
                  enumerable: descriptor ? !!descriptor.enumerable : true
                });
              } catch (e) {}
            }
            """.trimIndent()
        )
        sb.append("\n")

        // Do Not Track is only a preference signal; it does not block tracking.
        if (profile.dntMode == "ENABLE" || profile.dntMode == "DISABLE") {
            val dntValue = if (profile.dntMode == "ENABLE") "1" else "0"
            sb.append(
                """
                try {
                  __nxDef(navigator, 'doNotTrack', ${q(dntValue)});
                  __nxDef(window, 'doNotTrack', ${q(dntValue)});
                } catch (e) {}
                """.trimIndent()
            ).append("\n")
        }

        // Core identity values. Descriptor enumerability is preserved where possible.
        sb.append(
            """
            try {
              __nxDef(navigator, 'hardwareConcurrency', ${preset.cores});
              __nxDef(navigator, 'deviceMemory', $deviceMemory);
              __nxDef(navigator, 'platform', ${q(platform)});
              __nxDef(navigator, 'language', ${q(language)});
              __nxDef(navigator, 'languages', [${q(language)}, ${q(language.substringBefore('-'))}]);
              __nxDef(navigator, 'maxTouchPoints', 5);
              __nxDef(navigator, 'webdriver', false);
            } catch (e) {}
            try {
              __nxDef(screen, 'width', $cssW);
              __nxDef(screen, 'height', $cssH);
              __nxDef(screen, 'availWidth', $cssW);
              __nxDef(screen, 'availHeight', $cssH);
              __nxDef(screen, 'colorDepth', 24);
              __nxDef(screen, 'pixelDepth', 24);
              __nxDef(window, 'devicePixelRatio', ${preset.dpr});
            } catch (e) {}
            """.trimIndent()
        ).append("\n")

        // Keep exposed UA Client Hints aligned with the live UA string when the
        // WebView actually provides the native API. Never fabricate API support.
        sb.append(
            """
            try {
              var __nxNativeUAData = navigator.userAgentData;
              if (__nxNativeUAData && typeof __nxNativeUAData.getHighEntropyValues === 'function') {
                function __nxUaInfo() {
                  var ua = String(navigator.userAgent || '');
                  var chrome = /(?:Chrome|CriOS)\/([0-9.]+)/i.exec(ua);
                  var android = /Android\s+([0-9.]+)/i.exec(ua);
                  var model = null;
                  if (android) {
                    var modelMatch = /Android\s+[^;]+;\s*([^;)]+)[;)]/i.exec(ua);
                    if (modelMatch && modelMatch[1]) model = modelMatch[1].trim().replace(/\s+Build\/.*$/i, '');
                  }
                  var platform = __nxNativeUAData.platform;
                  if (android) platform = 'Android';
                  else if (/iPhone|iPad|iPod/i.test(ua)) platform = 'iOS';
                  else if (/Windows/i.test(ua)) platform = 'Windows';
                  else if (/Mac OS X|Macintosh/i.test(ua)) platform = 'macOS';
                  else if (/CrOS/i.test(ua)) platform = 'Chrome OS';
                  else if (/Linux/i.test(ua)) platform = 'Linux';
                  var platformVersion = android ? android[1] : null;
                  if (platformVersion) {
                    var parts = platformVersion.split('.');
                    while (parts.length < 4) parts.push('0');
                    platformVersion = parts.slice(0, 4).join('.');
                  }
                  return {
                    chromeVersion: chrome ? chrome[1] : null,
                    chromeMajor: chrome ? chrome[1].split('.')[0] : null,
                    androidModel: model,
                    platform: platform,
                    platformVersion: platformVersion,
                    mobile: /Android|Mobile|iPhone|iPad|iPod/i.test(ua)
                  };
                }
                function __nxMapBrands(brands, full) {
                  var info = __nxUaInfo();
                  return (brands || []).map(function(item) {
                    var name = String(item.brand || '');
                    var isChromium = /Chromium|Google Chrome|Chrome/i.test(name);
                    var version = item.version;
                    if (isChromium && info.chromeVersion) version = full ? info.chromeVersion : info.chromeMajor;
                    return { brand: name, version: version };
                  });
                }
                var __nxUAData = {};
                Object.defineProperties(__nxUAData, {
                  brands: { enumerable: true, get: function() {
                    return __nxMapBrands(__nxNativeUAData.brands, false);
                  }},
                  mobile: { enumerable: true, get: function() { return __nxUaInfo().mobile; }},
                  platform: { enumerable: true, get: function() { return __nxUaInfo().platform; }},
                  getHighEntropyValues: { enumerable: true, configurable: true, value: function(hints) {
                    return Promise.resolve(__nxNativeUAData.getHighEntropyValues.call(__nxNativeUAData, hints)).then(function(result) {
                      var copy = Object.assign({}, result || {});
                      var info = __nxUaInfo();
                      var requested = Array.isArray(hints) ? hints : [];
                      if (requested.indexOf('platform') >= 0) copy.platform = info.platform;
                      if (requested.indexOf('mobile') >= 0) copy.mobile = info.mobile;
                      if (requested.indexOf('model') >= 0 && info.androidModel) copy.model = info.androidModel;
                      if (requested.indexOf('platformVersion') >= 0 && info.platformVersion) copy.platformVersion = info.platformVersion;
                      if (requested.indexOf('fullVersionList') >= 0 && info.chromeVersion) {
                        copy.fullVersionList = __nxMapBrands(result.fullVersionList || __nxNativeUAData.brands, true);
                      }
                      return copy;
                    });
                  }}
                });
                __nxDef(navigator, 'userAgentData', __nxUAData);
              }
            } catch (e) {}
            """.trimIndent()
        ).append("\n")

        if (profile.webglSpoof) {
            sb.append(
                """
                try {
                  var __nxVendor = ${q(vendor)};
                  var __nxRenderer = ${q(renderer)};
                  var __nxGLPatched = (typeof WeakSet !== 'undefined') ? new WeakSet() : null;
                  var __nxGLDebugCache = (typeof WeakMap !== 'undefined') ? new WeakMap() : null;
                  function __nxPatchGL(proto) {
                    if (!proto || !proto.getParameter || (__nxGLPatched && __nxGLPatched.has(proto))) return;
                    var originalGetParameter = proto.getParameter;
                    var originalGetExtension = proto.getExtension;
                    function hasDebugInfo(gl) {
                      if (!originalGetExtension) return false;
                      if (__nxGLDebugCache && __nxGLDebugCache.has(gl)) return __nxGLDebugCache.get(gl);
                      var supported = false;
                      try { supported = !!originalGetExtension.call(gl, 'WEBGL_debug_renderer_info'); } catch (e) {}
                      if (__nxGLDebugCache) __nxGLDebugCache.set(gl, supported);
                      return supported;
                    }
                    proto.getParameter = function(parameter) {
                      try {
                        if (parameter === this.VENDOR) return __nxVendor;
                        if (parameter === this.RENDERER) return __nxRenderer;
                        if (parameter === 37445 && hasDebugInfo(this)) return __nxVendor;
                        if (parameter === 37446 && hasDebugInfo(this)) return __nxRenderer;
                      } catch (e) {}
                      return originalGetParameter.apply(this, arguments);
                    };
                    if (__nxGLPatched) __nxGLPatched.add(proto);
                  }
                  if (window.WebGLRenderingContext) __nxPatchGL(WebGLRenderingContext.prototype);
                  if (window.WebGL2RenderingContext) __nxPatchGL(WebGL2RenderingContext.prototype);
                } catch (e) {}
                """.trimIndent()
            ).append("\n")
        }

        if (profile.canvasNoise) {
            sb.append(
                """
                try {
                  var __nxCanvasSeed = $seed;
                  function __nxNoisePixels(imageData, width, height, salt) {
                    var data = imageData && imageData.data;
                    if (!data || !data.length) return imageData;
                    var pixels = Math.floor(data.length / 4);
                    var random = __nxMulberry32(__nxCanvasSeed ^ ((width * 131 + height) | 0) ^ (salt || 0));
                    var stride = pixels > 262144 ? 997 : 613;
                    var start = Math.floor(random() * stride);
                    for (var pixel = start; pixel < pixels; pixel += stride) {
                      var index = pixel * 4;
                      var delta = random() < 0.5 ? -1 : 1;
                      var red = data[index] + delta;
                      data[index] = red < 0 ? 0 : (red > 255 ? 255 : red);
                    }
                    return imageData;
                  }
                  var __nxCanvasPatched = (typeof WeakSet !== 'undefined') ? new WeakSet() : null;
                  function __nxPatchCanvasContext(proto) {
                    if (!proto || !proto.getImageData || (__nxCanvasPatched && __nxCanvasPatched.has(proto))) return;
                    var originalGetImageData = proto.getImageData;
                    proto.getImageData = function() {
                      var image = originalGetImageData.apply(this, arguments);
                      try { __nxNoisePixels(image, Number(arguments[2]) || 0, Number(arguments[3]) || 0, 0); } catch (e) {}
                      return image;
                    };
                    if (__nxCanvasPatched) __nxCanvasPatched.add(proto);
                  }
                  if (window.CanvasRenderingContext2D) __nxPatchCanvasContext(CanvasRenderingContext2D.prototype);
                  if (window.OffscreenCanvasRenderingContext2D) __nxPatchCanvasContext(OffscreenCanvasRenderingContext2D.prototype);
                  if (window.OffscreenCanvas) {
                    try {
                      var __nxOffscreenProbe = new OffscreenCanvas(1, 1).getContext('2d');
                      if (__nxOffscreenProbe) __nxPatchCanvasContext(Object.getPrototypeOf(__nxOffscreenProbe));
                    } catch (e) {}
                  }

                  function __nxCanvasExportCopy(source, offscreen) {
                    var width = Number(source.width) || 0;
                    var height = Number(source.height) || 0;
                    var copy = offscreen && window.OffscreenCanvas
                      ? new OffscreenCanvas(width, height)
                      : document.createElement('canvas');
                    copy.width = width;
                    copy.height = height;
                    var context = copy.getContext('2d', { willReadFrequently: true });
                    if (!context) return null;
                    context.drawImage(source, 0, 0);
                    if (width > 0 && height > 0) {
                      var image = context.getImageData(0, 0, width, height);
                      context.putImageData(image, 0, 0);
                    }
                    return copy;
                  }

                  if (window.HTMLCanvasElement && HTMLCanvasElement.prototype.toDataURL) {
                    var __nxNativeToDataURL = HTMLCanvasElement.prototype.toDataURL;
                    HTMLCanvasElement.prototype.toDataURL = function() {
                      try {
                        var copy = __nxCanvasExportCopy(this, false);
                        if (copy) return __nxNativeToDataURL.apply(copy, arguments);
                      } catch (e) {}
                      return __nxNativeToDataURL.apply(this, arguments);
                    };
                  }
                  if (window.HTMLCanvasElement && HTMLCanvasElement.prototype.toBlob) {
                    var __nxNativeToBlob = HTMLCanvasElement.prototype.toBlob;
                    HTMLCanvasElement.prototype.toBlob = function(callback, type, quality) {
                      var target = this;
                      try { target = __nxCanvasExportCopy(this, false) || this; } catch (e) { target = this; }
                      return __nxNativeToBlob.call(target, callback, type, quality);
                    };
                  }
                  if (window.OffscreenCanvas && OffscreenCanvas.prototype.convertToBlob) {
                    var __nxNativeConvertToBlob = OffscreenCanvas.prototype.convertToBlob;
                    OffscreenCanvas.prototype.convertToBlob = function(options) {
                      var target = this;
                      try { target = __nxCanvasExportCopy(this, true) || this; } catch (e) { target = this; }
                      return __nxNativeConvertToBlob.call(target, options);
                    };
                  }
                } catch (e) {}
                """.trimIndent()
            ).append("\n")
        }

        if (profile.audioNoise) {
            sb.append(
                """
                try {
                  if (window.AudioBuffer && AudioBuffer.prototype.getChannelData) {
                    var __nxAudioSeed = $seed;
                    var __nxAudioState = (typeof WeakMap !== 'undefined') ? new WeakMap() : null;
                    var __nxOriginalGetChannelData = AudioBuffer.prototype.getChannelData;
                    var __nxOriginalCopyFromChannel = AudioBuffer.prototype.copyFromChannel;
                    var __nxOriginalCopyToChannel = AudioBuffer.prototype.copyToChannel;
                    function __nxAudioApply(buffer, channel) {
                      if (!__nxAudioState) return;
                      var channels = __nxAudioState.get(buffer);
                      if (!channels) { channels = {}; __nxAudioState.set(buffer, channels); }
                      var key = String(channel);
                      if (channels[key]) return;
                      var samples = __nxOriginalGetChannelData.call(buffer, channel);
                      var random = __nxMulberry32(__nxAudioSeed ^ 0xA0D10 ^ (Number(channel) * 0x45D9F3B));
                      var count = Math.min(4096, samples.length);
                      for (var i = 0; i < count; i++) samples[i] += (random() - 0.5) * 1e-7;
                      channels[key] = true;
                    }
                    AudioBuffer.prototype.getChannelData = function(channel) {
                      try { __nxAudioApply(this, channel); } catch (e) {}
                      return __nxOriginalGetChannelData.apply(this, arguments);
                    };
                    if (__nxOriginalCopyFromChannel) {
                      AudioBuffer.prototype.copyFromChannel = function(destination, channel) {
                        try { __nxAudioApply(this, channel); } catch (e) {}
                        return __nxOriginalCopyFromChannel.apply(this, arguments);
                      };
                    }
                    if (__nxOriginalCopyToChannel) {
                      AudioBuffer.prototype.copyToChannel = function(source, channel) {
                        var result = __nxOriginalCopyToChannel.apply(this, arguments);
                        try {
                          if (__nxAudioState) {
                            var channels = __nxAudioState.get(this);
                            if (channels) delete channels[String(channel)];
                          }
                        } catch (e) {}
                        return result;
                      };
                    }
                  }
                } catch (e) {}
                """.trimIndent()
            ).append("\n")
        }

        if (profile.timezoneId.isNotEmpty()) {
            sb.append(
                """
                try {
                  var __nxTz = ${q(profile.timezoneId)};
                  var __nxOrigResolved = Intl.DateTimeFormat.prototype.resolvedOptions;
                  Intl.DateTimeFormat.prototype.resolvedOptions = function() {
                    var options = __nxOrigResolved.call(this);
                    try { options.timeZone = __nxTz; } catch (e2) {}
                    return options;
                  };
                  function __nxTzOffsetMs(date, tz) {
                    var formatter = new Intl.DateTimeFormat('en-US', {
                      timeZone: tz, hour12: false,
                      year: 'numeric', month: '2-digit', day: '2-digit',
                      hour: '2-digit', minute: '2-digit', second: '2-digit'
                    });
                    var parts = formatter.formatToParts(date);
                    var values = {};
                    for (var i = 0; i < parts.length; i++) values[parts[i].type] = parts[i].value;
                    var hour = parseInt(values.hour, 10);
                    if (hour === 24) hour = 0;
                    var asUTC = Date.UTC(
                      parseInt(values.year, 10), parseInt(values.month, 10) - 1, parseInt(values.day, 10),
                      hour, parseInt(values.minute, 10), parseInt(values.second, 10)
                    );
                    return asUTC - Math.floor(date.getTime() / 1000) * 1000;
                  }
                  Date.prototype.getTimezoneOffset = function() {
                    return -Math.round(__nxTzOffsetMs(this, __nxTz) / 60000);
                  };
                } catch (e) {}
                """.trimIndent()
            ).append("\n")
        }

        if (profile.webrtcBlock) {
            sb.append(
                """
                try {
                  Object.defineProperty(window, 'RTCPeerConnection', {
                    value: undefined, configurable: true, writable: true
                  });
                } catch (e) {
                  try { window.RTCPeerConnection = undefined; } catch (e2) {}
                }
                try {
                  Object.defineProperty(window, 'webkitRTCPeerConnection', {
                    value: undefined, configurable: true, writable: true
                  });
                } catch (e) {
                  try { window.webkitRTCPeerConnection = undefined; } catch (e2) {}
                }
                """.trimIndent()
            ).append("\n")
        }

        sb.append("})();\n")
        return sb.toString()
    }

    /** Context exposed only to the bundled local inspector, never to remote pages. */
    fun diagnosticsContextJson(
        profile: BrowserProfile,
        preset: DevicePreset,
        userAgent: String,
        documentStartSupported: Boolean
    ): String {
        return JSONObject()
            .put("expectedUserAgent", userAgent)
            .put("expectedPlatform", if (preset.isIos) "iPhone" else "Linux armv8l")
            .put("expectedLanguage", profile.language.ifEmpty { "en-US" })
            .put("expectedCores", preset.cores)
            .put("expectedMemory", deviceMemoryBucket(profile.ramGb))
            .put("expectedScreenWidth", preset.cssWidth)
            .put("expectedScreenHeight", preset.cssHeight)
            .put("expectedDpr", preset.dpr)
            .put("documentStartSupported", documentStartSupported)
            .put("webglSpoofEnabled", profile.webglSpoof)
            .put("expectedWebglVendor", if (profile.webglSpoof) webglVendor(preset) else "")
            .put("expectedWebglRenderer", if (profile.webglSpoof) preset.gpu else "")
            .put("canvasNoiseEnabled", profile.canvasNoise)
            .put("audioNoiseEnabled", profile.audioNoise)
            .put("webrtcBlocked", profile.webrtcBlock)
            .toString()
    }

    private fun prngSource(): String =
        """
        function __nxMulberry32(a) {
          return function() {
            a |= 0; a = (a + 0x6D2B79F5) | 0;
            var t = Math.imul(a ^ (a >>> 15), 1 | a);
            t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
            return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
          };
        }
        """.trimIndent() + "\n"

    private fun effectiveSeed(profile: BrowserProfile): Int {
        if (profile.seed != 0) return profile.seed
        val fromId = profile.id.hashCode()
        return if (fromId != 0) fromId else 0x4E584F52
    }

    /** navigator.deviceMemory uses coarse power-of-two buckets, capped at 8 GB. */
    private fun deviceMemoryBucket(ramGb: Int): Int {
        var bucket = 1
        while (bucket * 2 <= ramGb && bucket < 8) bucket *= 2
        return bucket
    }

    private fun webglVendor(preset: DevicePreset): String = when {
        preset.isIos -> "Apple Inc."
        preset.gpu.startsWith("Adreno") -> "Qualcomm"
        preset.gpu.startsWith("Mali") -> "ARM"
        preset.gpu.startsWith("Apple") -> "Apple Inc."
        else -> "Qualcomm"
    }

    private fun q(value: String): String = JSONObject.quote(value)
}
