/**
 * @file Menu.kt
 * @brief メニュー項目を簡単に作るための拡張関数。
 */
package com.google.android.diskusage.utils

import android.content.res.ColorStateList
import android.os.Build
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import splitties.resources.drawable

/**
 * @brief メニュー項目のアイコン、表示方法、クリック時の処理を設定する。
 * @param icon アイコン(0 なら設定しない)
 * @param iconTint アイコンの色(0 なら設定しない)
 * @param showAsAction true なら余裕があればツールバーに表示する
 * @param onClick クリック時の処理。false を返したときだけ未処理とみなす
 * @return この項目
 */
fun MenuItem.setup(
    @DrawableRes icon: Int,
    @ColorInt iconTint: Int,
    showAsAction: Boolean,
    onClick: Function0<Any?>?
): MenuItem {
    if (icon != 0 && iconTint != 0) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            iconTintList = ColorStateList.valueOf(iconTint)
            setIcon(icon)
        } else {
            setIcon(AppHelper.appContext.drawable(icon)?.apply { setTint(iconTint) })
        }
    }
    if (showAsAction) {
        setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }
    if (onClick != null) {
        setOnMenuItemClickListener {
            // return false only when the actual callback returns false
            onClick.invoke() != false
        }
    }
    return this
}

/**
 * @brief 文字列リソースのタイトルでメニュー項目を追加する。
 * @param title タイトルの文字列リソース
 * @param icon アイコン
 * @param iconTint アイコンの色
 * @param showAsAction true なら余裕があればツールバーに表示する
 * @param onClick クリック時の処理
 * @return 追加した項目
 */
fun Menu.item(
    @StringRes title: Int,
    @DrawableRes icon: Int = 0,
    @ColorInt iconTint: Int = 0,
    showAsAction: Boolean = false,
    onClick: Function0<Any?>? = null
): MenuItem {
    val item = add(title).setup(icon, iconTint, showAsAction, onClick)
    return item
}

/**
 * @brief 文字列のタイトルでメニュー項目を追加する。
 * @param title タイトル
 * @param icon アイコン
 * @param iconTint アイコンの色
 * @param showAsAction true なら余裕があればツールバーに表示する
 * @param onClick クリック時の処理
 * @return 追加した項目
 */
fun Menu.item(
    title: CharSequence,
    @DrawableRes icon: Int = 0,
    @ColorInt iconTint: Int = 0,
    showAsAction: Boolean = false,
    onClick: Function0<Any?>? = null
): MenuItem {
    val item = add(title).setup(icon, iconTint, showAsAction, onClick)
    return item
}

/**
 * @brief サブメニューを追加する。
 * @param title タイトルの文字列リソース
 * @param icon アイコン
 * @param iconTint アイコンの色
 * @param showAsAction true なら余裕があればツールバーに表示する
 * @param initSubMenu サブメニューの項目を追加する処理
 * @return 追加したサブメニュー
 */
fun Menu.subMenu(
    @StringRes title: Int,
    @DrawableRes icon: Int,
    @ColorInt iconTint: Int,
    showAsAction: Boolean = false,
    initSubMenu: SubMenu.() -> Unit
): SubMenu {
    val sub = addSubMenu(title)
    sub.item.setup(icon, iconTint, showAsAction, null)
    initSubMenu.invoke(sub)
    return sub
}