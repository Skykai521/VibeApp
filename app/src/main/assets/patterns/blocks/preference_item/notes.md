For a Switch preference, replace the trailing TextView with
`androidx.appcompat.widget.SwitchCompat` (MaterialSwitch is NOT bundled).
Set the root as clickable and toggle the switch in the click handler.
For a navigation row (opens another screen), put a chevron drawable
in the trailing TextView via `drawableEnd`.
