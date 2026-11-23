package com.nexora.browser.fp

import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DevicePreset
import org.json.JSONObject

/**
 * Builds the document-start JavaScript that applies a profile's fingerprint
 * at JS level (Stage A). Every override sits in its own try/catch so one
 * failing block never breaks the page or the other blocks.
 *
 * Known Stage A limit: values set here are visible to pages, but advanced
 * checkers can spot JS-level patching (e.g. via workers or native signals).
 * Stage B moves all of this into the engine.
 */
object FingerprintScript {

    fun build(profile: BrowserProfile, preset: DevicePreset): String {
        val seed = profile.seed
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
            function __nxDef(obj, prop, val) {
              try {
                Object.defineProperty(obj, prop, { get: function() { return val; }, configurable: true });
              } catch (e) {}
            }
            """.trimIndent()
        )
        sb.append("\n")

        // ---- Do Not Track preference signal (a request signal, not an enforcement mechanism) ----
        if (profile.dntMode == "ENABLE" || profile.dntMode == "DISABLE") {
            val dntValue = if (profile.dntMode == "ENABLE") "1" else "0"
            sb.append(
                """
                try {
                  __nxDef(navigator, 'doNotTrack', ${q(dntValue)});
                  __nxDef(window, 'doNotTrack', ${q(dntValue)});
                } catch (e) {}
                """.trimIndent()
            )
            sb.append("\n")
        }

        // ---- navigator + screen (always on: this is the profile's identity) ----
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
        )
        sb.append("\n")

        if (profile.webglSpoof) {
            sb.append(
                """
                try {
                  var __nxVendor = ${q(vendor)};
                  var __nxRenderer = ${q(renderer)};
                  function __nxPatchGL(proto) {
                    if (!proto || !proto.getParameter) return;
                    var orig = proto.getParameter;
                    proto.getParameter = function(p) {
                      if (p === 37445) return __nxVendor;
                      if (p === 37446) return __nxRenderer;
                      return orig.call(this, p);
                    };
                  }
                  if (window.WebGLRenderingContext) __nxPatchGL(WebGLRenderingContext.prototype);
                  if (window.WebGL2RenderingContext) __nxPatchGL(WebGL2RenderingContext.prototype);
                } catch (e) {}
                """.trimIndent()
            )
            sb.append("\n")
        }

        if (profile.canvasNoise) {
            sb.append(
                """
                try {
                  if (window.CanvasRenderingContext2D && CanvasRenderingContext2D.prototype.getImageData) {
                    var __nxOrigGID = CanvasRenderingContext2D.prototype.getImageData;
                    CanvasRenderingContext2D.prototype.getImageData = function(x, y, w, h) {
                      var img = __nxOrigGID.call(this, x, y, w, h);
                      try {
                        var d = img.data;
                        var rnd = __nxMulberry32($seed ^ ((w * 131 + h) | 0));
                        var stride = 613 + Math.floor(rnd() * 500);
                        var pixels = d.length / 4;
                        for (var p = Math.floor(rnd() * stride); p < pixels; p += stride) {
                          var i = p * 4;
                          var delta = Math.floor(rnd() * 3) - 1;
                          var v = d[i] + delta;
                          d[i] = v < 0 ? 0 : (v > 255 ? 255 : v);
                        }
                      } catch (e2) {}
                      return img;
                    };
                  }
                } catch (e) {}
                """.trimIndent()
            )
            sb.append("\n")
        }

        if (profile.audioNoise) {
            sb.append(
                """
                try {
                  if (window.AudioBuffer && AudioBuffer.prototype.getChannelData) {
                    var __nxSeen = (typeof WeakSet !== 'undefined') ? new WeakSet() : null;
                    var __nxOrigGCD = AudioBuffer.prototype.getChannelData;
                    AudioBuffer.prototype.getChannelData = function(ch) {
                      var buf = __nxOrigGCD.call(this, ch);
                      try {
                        if (__nxSeen && !__nxSeen.has(this)) {
                          __nxSeen.add(this);
                          var rnd = __nxMulberry32($seed ^ 0xA0D10);
                          var n = Math.min(1000, buf.length);
                          for (var i = 0; i < n; i++) {
                            buf[i] = buf[i] + (rnd() - 0.5) * 1e-7;
                          }
                        }
                      } catch (e2) {}
                      return buf;
                    };
                  }
                } catch (e) {}
                """.trimIndent()
            )
            sb.append("\n")
        }

        if (profile.timezoneId.isNotEmpty()) {
            sb.append(
                """
                try {
                  var __nxTz = ${q(profile.timezoneId)};
                  var __nxOrigResolved = Intl.DateTimeFormat.prototype.resolvedOptions;
                  Intl.DateTimeFormat.prototype.resolvedOptions = function() {
                    var o = __nxOrigResolved.call(this);
                    try { o.timeZone = __nxTz; } catch (e2) {}
                    return o;
                  };
                  function __nxTzOffsetMs(date, tz) {
                    var dtf = new Intl.DateTimeFormat('en-US', {
                      timeZone: tz, hour12: false,
                      year: 'numeric', month: '2-digit', day: '2-digit',
                      hour: '2-digit', minute: '2-digit', second: '2-digit'
                    });
                    var parts = dtf.formatToParts(date);
                    var m = {};
                    for (var i = 0; i < parts.length; i++) { m[parts[i].type] = parts[i].value; }
                    var hour = parseInt(m.hour, 10);
                    if (hour === 24) hour = 0;
                    var asUTC = Date.UTC(
                      parseInt(m.year, 10), parseInt(m.month, 10) - 1, parseInt(m.day, 10),
                      hour, parseInt(m.minute, 10), parseInt(m.second, 10)
                    );
                    return asUTC - Math.floor(date.getTime() / 1000) * 1000;
                  }
                  Date.prototype.getTimezoneOffset = function() {
                    return -Math.round(__nxTzOffsetMs(this, __nxTz) / 60000);
                  };
                } catch (e) {}
                """.trimIndent()
            )
            sb.append("\n")
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
            )
            sb.append("\n")
        }

        sb.append("})();\n")
        return sb.toString()
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

    /** navigator.deviceMemory only ever reports power-of-two buckets, capped at 8. */
    private fun deviceMemoryBucket(ramGb: Int): Int {
        var bucket = 1
        while (bucket * 2 <= ramGb && bucket < 8) bucket *= 2
        return bucket
    }

    /** The WebGL unmasked vendor that matches the preset's GPU family. */
    private fun webglVendor(preset: DevicePreset): String = when {
        preset.isIos -> "Apple Inc."
        preset.gpu.startsWith("Adreno") -> "Qualcomm"
        preset.gpu.startsWith("Mali") -> "ARM"
        preset.gpu.startsWith("Apple") -> "Apple Inc."
        else -> "Qualcomm"
    }

    /** Safely quoted JS string literal. */
    private fun q(value: String): String = JSONObject.quote(value)
}
