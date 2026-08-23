package com.example.helloandroid

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class MainActivity : Activity() {

    private var shelf: ShelfView? = null
    private var settings: SettingsView? = null
    private var current: android.view.View? = null

    private fun setRoot(v: android.view.View) { current = v; setContentView(v) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 支持自动化验证直达阅读器：am start --es bookId 1
        val bid = intent.getStringExtra("bookId")?.toLongOrNull() ?: -1L
        if (bid > 0) openReader(bid) else showShelf()
    }

    fun showShelf() {
        val s = ShelfView(this)
        shelf = s
        setRoot(s)
    }

    fun openReader(id: Long) {
        Db(this).markOpened(id)
        setRoot(ReaderView(this, id))
    }

    fun showSettings() {
        val s = SettingsView(this)
        settings = s
        setRoot(s)
    }

    fun showNotes(bookId: Long) {
        setRoot(NotesView(this, bookId))
    }

    fun showChat(bookId: Long, chapterContext: String) {
        setRoot(ChatView(this, bookId, chapterContext))
    }

    fun showStats() {
        setRoot(StatsView(this))
    }

    /** 从统计页返回书架（书架重建，保持最新数据） */
    fun backToShelf() { showShelf() }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        (current as? ReaderView)?.let { rv ->
            if (rv.handleVolumeKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Glass.REQ_SETTINGS) { shelf?.refresh(); return }
        if (requestCode == SettingsView.REQ_RESTORE) {
            if (resultCode == Activity.RESULT_OK) settings?.handleRestoreResult(data?.data ?: return)
            return
        }
        if (requestCode == SettingsView.REQ_EXPORT) {
            if (resultCode == Activity.RESULT_OK) settings?.handleExportResult(data?.data ?: return)
            return
        }
        shelf?.handleResult(requestCode, data)
    }
}
