/* Live face capture following the server's random pose challenge. Frames go straight to the
   server for liveness + 1:1 comparison and are discarded there; nothing is stored on the device. */
(function () {
  "use strict";
  const PROMPT = { CENTER: "Look straight ahead", LEFT: "Turn your head left", RIGHT: "Turn your head right" };

  /**
   * Mounts a capture UI in `root`, runs `challenge.steps`, then calls upload(formData) and returns its result.
   * Throws ApiError with a clear message for camera problems.
   */
  SA.faceCapture = async function (root, challenge, upload) {
    root.innerHTML = `<div class="face-stage full"><video playsinline muted autoplay></video><div class="oval"></div>
      <span class="arrow left hidden" aria-hidden="true">‹</span><span class="arrow right hidden" aria-hidden="true">›</span>
      <div class="instruction" role="status" aria-live="assertive">Starting camera…</div></div>`;
    const video = SA.$("video", root), oval = SA.$(".oval", root), say = SA.$(".instruction", root);
    const stage = SA.$(".face-stage", root);
    const unfull = () => stage.classList.remove("full"); // back to the page when done or on any error
    const arrowL = SA.$(".arrow.left", root), arrowR = SA.$(".arrow.right", root);
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      unfull();
      throw new SA.ApiError(0, "NO_CAMERA", "This browser cannot use the camera. Open SmartAttend in Chrome, Safari or Edge.");
    }
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "user", width: { ideal: 1280 }, height: { ideal: 1280 } }, audio: false });
    } catch (e) {
      const msg = e && e.name === "NotAllowedError"
        ? "Camera permission denied. Allow camera access for this site in your browser settings, then try again."
        : e && e.name === "NotFoundError" ? "No camera was found on this device." : "The camera could not be started. Close other apps using it and try again.";
      unfull();
      throw new SA.ApiError(0, "CAMERA", msg);
    }
    const stop = () => stream.getTracks().forEach((t) => t.stop());
    try {
      video.srcObject = stream;
      await new Promise((r) => (video.readyState >= 2 ? r() : (video.onloadeddata = r)));
      const canvas = document.createElement("canvas");
      const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
      const grab = () => new Promise((resolve) => {
        const w = video.videoWidth, h = video.videoHeight, scale = Math.min(1, 720 / Math.max(w, h));
        canvas.width = Math.round(w * scale); canvas.height = Math.round(h * scale);
        canvas.getContext("2d").drawImage(video, 0, 0, canvas.width, canvas.height); // raw (un-mirrored) frame
        canvas.toBlob((b) => resolve(b), "image/jpeg", 0.85);
      });
      say.textContent = "Fill the oval with your face";
      await sleep(1800);
      const frames = [];
      for (const step of challenge.steps) {
        say.textContent = PROMPT[step] || step;
        // Arrows show the direction on the mirrored preview (the user's left appears on screen-left).
        arrowL.classList.toggle("hidden", step !== "LEFT");
        arrowR.classList.toggle("hidden", step !== "RIGHT");
        oval.classList.remove("capture");
        await sleep(step === "CENTER" ? 1500 : 2200);
        oval.classList.add("capture");
        frames.push(await grab());
        await sleep(250);
      }
      arrowL.classList.add("hidden"); arrowR.classList.add("hidden");
      say.textContent = "Verifying…";
      stop();
      const fd = new FormData();
      fd.append("challengeId", challenge.challengeId);
      frames.forEach((b, i) => fd.append("frames", b, "frame" + i + ".jpg"));
      return await upload(fd);
    } finally {
      stop();
      unfull();
    }
  };
})();
