# Dynamic VT-2D — implementation roadmap

This document separates features that can be delivered safely from behaviors that must be validated on a physical Android device.

## Capture and recording findings

- Use Android MediaProjection only after the user explicitly approves the system capture prompt for each recording session.
- On Android 14+ the recording service must declare `FOREGROUND_SERVICE_MEDIA_PROJECTION`, use the `mediaProjection` foreground-service type, and follow the one-consent-token/one-virtual-display rules.
- A full-display MediaProjection records the composed display. It does not provide a supported universal switch that hides an arbitrary overlay from the recorded output while keeping that same overlay visible locally.
- `FLAG_SECURE` can exclude a window from screenshots/capture, but the resulting captured region may be blank/black. It is not a reliable way to achieve a clean composited VTuber video by itself.
- Therefore, do not advertise “menu excluded with no black patch” or “avatar opaque in video while translucent locally” until a compositing pipeline has been implemented and verified on real devices.

## Recommended phases

### Phase A — preserve stable LIVE behavior
- Keep menu visibility independent of avatar opacity/lock state.
- Keep avatar position clamped after orientation changes.
- Keep voice and camera permission/error paths recoverable.
- Run the Android debug build after every code change.

### Phase B — integrated screen recording
1. Request capture consent from an Activity, not silently from the overlay service.
2. Start a foreground service with the required media-projection type and notification.
3. Record to MP4 and publish through MediaStore so the video appears in the gallery.
4. Handle pause/resume/stop, projection revocation, rotation, storage failures, and cleanup.
5. Hide the operator menu for the entire recording session and restore its previous visibility when recording ends.
6. Test whether the avatar can be separated from the background without exposing a black rectangle. If not, document the limitation rather than shipping a misleading mode.

### Phase C — opaque avatar composition
- Keep the live overlay opacity as a display preference.
- Render the avatar into a separate transparent video layer at full alpha, preserving the active expression, blink, speaking motion and hit effect.
- Composite that layer with the captured background. This requires an implementation that has access to the background without the avatar baked into it; MediaProjection alone does not guarantee that separation for arbitrary third-party apps.
- Verify the resulting MP4 frame-by-frame on physical devices before treating this as complete.

## Acceptance checklist

- [ ] Build succeeds and APK artifact exists for the exact commit.
- [ ] Recording can start only after explicit Android capture consent.
- [ ] MP4 is playable and saved to the gallery.
- [ ] Menu and controls do not appear in the saved video.
- [ ] No black/opaque rectangle remains where the menu or avatar was.
- [ ] Video avatar is fully opaque while the local avatar can remain translucent.
- [ ] Voice/camera expressions, idle motion, speaking motion and effects remain synchronized.
- [ ] Rotation, cancel, stop, permission revocation and service cleanup are tested.

A CI build verifies compilation only. Device-specific capture behavior requires a physical-device test.
