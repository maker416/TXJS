/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.impl.formatRtsBoxHeat
import io.github.rwpp.impl.parseRtsBoxHeat
import io.github.rwpp.impl.parseRtsBoxHotResources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RtsBoxHotResourcesParserTest {
    @Test
    fun parseHotResources_extractsOrderedCardsFromHtml() {
        val html = """
            <span class="lt_download_list_top_titel">热门资源</span>
            <span class="lt_download_list_top_titel_span lt_value" data-post_id="48175,48156,47808"
                  onclick="lt_hot_download_list(this)"></span>
            <span style="color: white;background:#f00f00;">独家_原矿题材神作</span>
            <div class="lt_position_relative">
              <a href="https://www.rtsbox.cn/wp-content/themes/LightSNS/mobile/templates/page/post-bbs.php?post_id=48175&url=https://www.rtsbox.cn/48175.html&type=bbs">
                <img src="https://css.rtsbox.cn/a.jpeg!webp" class="lt_br_2" alt="">
              </a>
              <span class="lt_title_span">气象灾害 1.20.32</span>
              <i class="jinsom-icon jinsom-fire-fill"></i>
              <span class="lt_text_shadow">6411</span>
            </div>
            <div class="jinsom-bbs-list-lattice jinsom-post-48156">
              <a href="https://www.rtsbox.cn/48156.html">
                <div style="background-image:url(https://css.rtsbox.cn/b.jpeg!webp)" class="bg">
                  <span class="lt_text_shadow">5826</span>
                </div>
                <div class="content"><div class="title">天使模0.0.8</div></div>
              </a>
              <img src="https://avatar.example/1.png" width="20" height="20">
              <span><a href="https://www.rtsbox.cn/author/90242" target="_blank">铁锈工作室</a></span>
            </div>
            <div class="jinsom-bbs-list-lattice jinsom-post-47808">
              <a href="https://www.rtsbox.cn/47808.html">
                <div style="background-image:url(https://css.rtsbox.cn/c.jpeg!webp)" class="bg">
                  <span style="background:#cc0000;">独家_太空题材神作</span>
                  <i class="jinsom-icon jinsom-fire-fill"></i>
                  <span class="lt_text_shadow">2.12w</span>
                </div>
                <div class="content"><div class="title">RLLB联邦内战2.5</div></div>
              </a>
              <span><a href="https://www.rtsbox.cn/author/44571" target="_blank">RLLB联邦</a></span>
            </div>
            <span class="lt_download_list_top_titel">推荐资源</span>
        """.trimIndent()

        val list = parseRtsBoxHotResources(html)
        assertEquals(3, list.size)

        assertEquals("48175", list[0].id)
        assertEquals("气象灾害 1.20.32", list[0].title)
        assertEquals("独家_原矿题材神作", list[0].description)
        assertEquals(6411, list[0].downloadNum)

        assertEquals("48156", list[1].id)
        assertEquals("铁锈工作室", list[1].author)
        assertEquals(5826, list[1].downloadNum)

        assertEquals("47808", list[2].id)
        assertEquals("独家_太空题材神作", list[2].description)
        assertEquals("RLLB联邦", list[2].author)
        assertEquals(21200, list[2].downloadNum)
        assertEquals("2.12w", formatRtsBoxHeat(list[2].downloadNum!!))
    }

    @Test
    fun parseAndFormatHeat_supportsWanSuffix() {
        assertEquals(21200, parseRtsBoxHeat("2.12w"))
        assertEquals(5852, parseRtsBoxHeat("5852"))
        assertEquals("2.12w", formatRtsBoxHeat(21200))
        assertEquals("5852", formatRtsBoxHeat(5852))
    }

    @Test
    fun parseHotResources_returnsEmptyWhenSectionMissing() {
        assertTrue(parseRtsBoxHotResources("<div>no hot</div>").isEmpty())
    }
}
