/* Passkey ceremonies. The browser/OS handles fingerprint, Face ID or Windows Hello; SmartAttend
   never receives biometric data — only a signature from the registered credential. */
(function () {
  "use strict";
  const b64u = {
    dec: (s) => { s = s.replace(/-/g, "+").replace(/_/g, "/"); while (s.length % 4) s += "="; return Uint8Array.from(atob(s), (c) => c.charCodeAt(0)).buffer; },
    enc: (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""),
  };

  SA.passkeySupported = async function () {
    if (!window.PublicKeyCredential || !navigator.credentials) return false;
    try { return await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable(); } catch (e) { return false; }
  };

  function explain(e) {
    if (e && e.name === "NotAllowedError") return "Passkey request was cancelled or timed out. Try again and confirm with your fingerprint, face or screen lock.";
    if (e && e.name === "InvalidStateError") return "This device already has a SmartAttend passkey for your account.";
    if (e && e.name === "SecurityError") return "Passkeys need a secure (https) connection to SmartAttend.";
    if (e && e.name === "NotSupportedError") return "This device or browser does not support passkeys.";
    return "Your device could not complete passkey verification.";
  }

  SA.createPasskey = async function (o) {
    const publicKey = Object.assign({}, o, {
      challenge: b64u.dec(o.challenge),
      user: Object.assign({}, o.user, { id: b64u.dec(o.user.id) }),
      excludeCredentials: (o.excludeCredentials || []).map((c) => Object.assign({}, c, { id: b64u.dec(c.id) })),
    });
    let cred;
    try { cred = await navigator.credentials.create({ publicKey }); } catch (e) { throw new SA.ApiError(0, "PASSKEY_CLIENT", explain(e)); }
    const r = cred.response;
    return {
      id: cred.id, rawId: b64u.enc(cred.rawId), type: cred.type,
      response: {
        clientDataJSON: b64u.enc(r.clientDataJSON), attestationObject: b64u.enc(r.attestationObject),
        transports: typeof r.getTransports === "function" ? r.getTransports() : [],
      },
      clientExtensionResults: cred.getClientExtensionResults ? cred.getClientExtensionResults() : {},
      authenticatorAttachment: cred.authenticatorAttachment || null,
    };
  };

  SA.usePasskey = async function (o) {
    const publicKey = Object.assign({}, o, {
      challenge: b64u.dec(o.challenge),
      allowCredentials: (o.allowCredentials || []).map((c) => Object.assign({}, c, { id: b64u.dec(c.id) })),
    });
    let cred;
    try { cred = await navigator.credentials.get({ publicKey }); } catch (e) { throw new SA.ApiError(0, "PASSKEY_CLIENT", explain(e)); }
    const r = cred.response;
    return {
      id: cred.id, rawId: b64u.enc(cred.rawId), type: cred.type,
      response: {
        clientDataJSON: b64u.enc(r.clientDataJSON), authenticatorData: b64u.enc(r.authenticatorData),
        signature: b64u.enc(r.signature), userHandle: r.userHandle ? b64u.enc(r.userHandle) : null,
      },
      clientExtensionResults: cred.getClientExtensionResults ? cred.getClientExtensionResults() : {},
      authenticatorAttachment: cred.authenticatorAttachment || null,
    };
  };
})();
