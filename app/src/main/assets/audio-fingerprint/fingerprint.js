"use strict";

let fingerprintRuntime;

async function makeFingerprint(requestId, encodedSamples) {
  try {
    if (!fingerprintRuntime) fingerprintRuntime = instantiateRuntime();
    const runtime = await fingerprintRuntime;
    const bytes = b64decode(encodedSamples);
    const result = runtime.ExtractQueryFP(bytes.buffer);
    try {
      const fingerprint = new Uint8Array(result.size());
      for (let i = 0; i < fingerprint.length; i++) fingerprint[i] = result.get(i);
      AudioFingerprint.finish(requestId, b64encode(fingerprint));
    } finally {
      result.delete();
    }
  } catch (_) {
    AudioFingerprint.finish(requestId, "");
  }
}
