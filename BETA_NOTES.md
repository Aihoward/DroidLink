# DroidLink 0.9.7 Beta

This build preserves the physically verified 0.9.2/0.9.3 Firebase, TURN/ICE,
WebRTC H.264 video, playback audio, controller transport, and Player 2 uinput
baseline.

0.9.7 preserves the physically verified 0.9.6 Winlator, PS2 mapping, media,
signaling, and session baseline. It consolidates D-pad KeyEvents and HAT events
into one reliable logical state stream, injects only ABS_HAT state changes, and
prevents duplicate or stale D-pad representations from oscillating in games.

`DroidLink Player 2` retains its visible name, generic evdev capabilities, and
Xbox 360 wired identity (VID/PID 045e:028e). The descriptor now uses Xbox-style
trigger ranges, correct X/Y evdev face-button positions, and avoids generating
duplicate trigger-button events from analog trigger motion.

## Winlator note

Start or restart the Winlator container after Logcat reports
`WINLATOR_GAMEPAD_READY`; many Wine/container input stacks enumerate controllers
only during startup. Android uinput identity cannot by itself guarantee that every
Winlator image exposes the device as XInput Controller 2. Final XInput, DirectInput,
and SDL behavior requires physical testing inside the selected container/game.

Filter physical-device Logcat with the tag `DroidLink`.
