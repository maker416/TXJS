/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/** Debug-only, isolated normal-engine launcher. Credentials are entered through normal native login. */
class GamePortalFixtureProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        check(context?.packageName == "io.github.rwjs.portaltest")
        System.setProperty("rwjs.account.apiUrl", "http://127.0.0.1:18080")
        System.setProperty("rwjs.account.appKey", "ak_payment_client_test")
        System.setProperty("rwjs.openPlatform.url", "http://127.0.0.1:18081")
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
