package com.vibe.app.presentation.common

object Route {
    const val SETUP_ROUTE = "setup_route"
    const val SETUP_PLATFORM_TYPE = "setup_platform_type"
    const val SETUP_PLATFORM_WIZARD = "setup_platform_wizard"
    const val SETUP_COMPLETE = "setup_complete"

    const val CHAT_LIST = "chat_list"
    const val CHAT_ROOM = "chat_room/{chatRoomId}?enabled={enabledPlatforms}"

    const val SETTING_ROUTE = "setting_route"
    const val SETTINGS = "settings"
    const val PLATFORM_SETTINGS = "platform_settings/{platformUid}"
    const val ABOUT_PAGE = "about"
    const val LICENSE = "license"
}
