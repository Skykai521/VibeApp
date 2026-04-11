Place at the top of an Activity layout. Do NOT call setSupportActionBar()
from Java — set the title via `toolbar.setTitle(...)` and wire the nav icon
via `toolbar.setNavigationOnClickListener(...)`. Drop the `navigationIcon`
attribute for root screens that have no back affordance.
