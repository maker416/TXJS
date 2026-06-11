/*
 * Copyright 2023-2025 RWPP contributors
 * 姝ゆ簮浠ｇ爜鐨勪娇鐢ㄥ彈 GNU AFFERO GENERAL PUBLIC LICENSE version 3 璁稿彲璇佺殑绾︽潫, 鍙互鍦ㄤ互涓嬮摼鎺ユ壘鍒拌璁稿彲璇?
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.external

data class FileChooseProgress(
    val fileName: String?,
    val copiedBytes: Long,
    val totalBytes: Long?
)
