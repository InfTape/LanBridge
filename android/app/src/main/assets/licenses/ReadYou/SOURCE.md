# Read You illustration

Source: https://github.com/ReadYouApp/ReadYou/blob/d2b979ccad9a3e54b9499929a9dc2824578b0fd9/app/src/main/java/me/ash/reader/ui/svg/palette.kt

Repository license: GNU GPL version 3; the complete text is in LICENSE.txt.

The original SVG path geometry is preserved in illustrations/recent_received_empty.svg, with valid light preview colors and the original Read You theme roles retained as data-theme-role attributes. Those roles (p50, s90, s50, s40, s30, t90, t40) are resolved to LanBridge Material 3 colors by ReceivedEmptyState.kt. SVG geometry is rendered using Compose's native ImageVector, rather than Read You's SVG rendering component. No upstream Kotlin rendering code was copied. The illustration is decorative and static.

Modified for LanBridge: native vector rendering, current Material theme colors, and empty-history layout/caption. Existing Read You branding and application code are not included.
