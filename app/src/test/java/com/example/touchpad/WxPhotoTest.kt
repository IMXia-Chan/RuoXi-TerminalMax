package com.example.touchpad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗外换天的**门槛**：什么天气、什么天光才值得把整张背景换掉。
 *
 * 他 2026-11-07 连着改了两回口：先要「按天气换整套照片」，收窄成「只在明显变天时换」，
 * 又补一句「晴天和阴天还是得区分一下」。这里钉的就是最后那条线：
 * **阴、雨、雪、雷、雾换；晴、多云不换**（用他自己配置的那张）。
 *
 * ★ 2026-11-13 补了一半：他晚上看见「房间背景还是早上那张」，而当时外面是**多云**。
 *   `room2c.jpg` 是白天拍的，白天那档不换没问题，**黄昏和夜里不能也不换**。
 *   所以现在这条线是二维的：**看天气，也看天光**。
 *
 * 这一条判错的症状是「外面没下雨，屋里换了张照片」—— 不报错、不崩，
 * 只是他某天晚上抬头一看会问「背景咋改了？」。所以它值得被钉住。
 */
class WxPhotoTest {

    @Test
    fun 阴雨雪雷雾要换() {
        // 白天也换：阴天和晴天在照片上是两回事，这一档 11-07 就定了。
        assertTrue(WxPhoto.swaps(SceneWeather.DAY, SceneWeather.OVERCAST))
        assertTrue(WxPhoto.swaps(SceneWeather.DAY, SceneWeather.RAIN))
        assertTrue(WxPhoto.swaps(SceneWeather.DAY, SceneWeather.SNOW))
        assertTrue(WxPhoto.swaps(SceneWeather.DAY, SceneWeather.THUNDER))
        assertTrue(WxPhoto.swaps(SceneWeather.DAY, SceneWeather.FOG))
    }

    @Test
    fun 白天晴天多云不换() {
        // 晴和多云合成一档：都是「外面亮着」，都留他自己那张。
        assertFalse(WxPhoto.swaps(SceneWeather.DAY, SceneWeather.CLEAR))
        assertFalse(WxPhoto.swaps(SceneWeather.DAY, SceneWeather.PARTLY))
    }

    @Test
    fun 黄昏和夜里晴天多云也要换() {
        // ★ 2026-11-13 那一条：时辰错了，整间屋子就错了。
        assertTrue(WxPhoto.swaps(SceneWeather.NIGHT, SceneWeather.CLEAR))
        assertTrue(WxPhoto.swaps(SceneWeather.NIGHT, SceneWeather.PARTLY))
        assertTrue(WxPhoto.swaps(SceneWeather.DUSK, SceneWeather.CLEAR))
        assertTrue(WxPhoto.swaps(SceneWeather.DUSK, SceneWeather.PARTLY))
    }

    @Test
    fun 认不出来就不换() {
        // 空串：天气没取到的时候可能会走到这儿。
        assertFalse(WxPhoto.swaps(SceneWeather.NIGHT, ""))
        // 大小写不一样也不认 —— 代号全是代码里自己拼的，不会有大写。
        assertFalse(WxPhoto.swaps(SceneWeather.NIGHT, "Rain"))
        // 将来多出来的代号：宁可不动。
        assertFalse(WxPhoto.swaps(SceneWeather.NIGHT, "hail"))
        // 天光认不出来时按白天算，也就是不换：宁可不换，也不拿一张白天的图冒充夜里。
        assertFalse(WxPhoto.swaps("", SceneWeather.CLEAR))
    }
}
