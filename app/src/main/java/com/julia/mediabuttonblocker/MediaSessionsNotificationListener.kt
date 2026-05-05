package com.julia.mediabuttonblocker

import android.service.notification.NotificationListenerService

/**
 * Empty [NotificationListenerService] subclass.
 *
 * We do **not** read any notifications, post any notifications, or otherwise act
 * on the notification stream. The only reason this class exists is that
 * [android.media.session.MediaSessionManager.addOnActiveSessionsChangedListener]
 * requires us to pass a [android.content.ComponentName] of a
 * [NotificationListenerService] declared by our app. The system uses that
 * component as the "permission proof" — once the user enables our app under
 * Settings → Apps → Special app access → Notification access (a one-off action),
 * any code in our process can subscribe to active-session changes through the
 * same component.
 *
 * The class deliberately overrides nothing. Android binds it (by design, when
 * the user grants notification access), no notifications are intercepted, and
 * nothing is ever posted from here. See [BlockerService] for the listener that
 * actually consumes the active-session change events.
 */
class MediaSessionsNotificationListener : NotificationListenerService()
