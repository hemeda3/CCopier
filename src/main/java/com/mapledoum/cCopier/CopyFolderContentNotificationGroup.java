package com.mapledoum.cCopier;

import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.openapi.components.Service;

@Service
public final class CopyFolderContentNotificationGroup {
    public static NotificationGroup getInstance() {
        return NotificationGroupManager.getInstance().getNotificationGroup("Copy Folder Content");
    }
}