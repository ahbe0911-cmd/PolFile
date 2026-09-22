package com.polfile.app
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

data class SharedFile(val uri: Uri, val name: String, val size: Long)
object FileStore {
 val revision=MutableStateFlow(0)
 fun add(context: Context, uris: List<Uri>) {
  val prefs=context.getSharedPreferences("shared",0)
  val saved=prefs.getStringSet("uris",emptySet()).orEmpty().toMutableSet()
  uris.forEach { saved.add(it.toString()) }
  prefs.edit().putStringSet("uris", saved).apply()
  revision.value++
 }
 fun list(context: Context):List<SharedFile> {
  return context.getSharedPreferences("shared",0).getStringSet("uris",emptySet()).orEmpty().mapNotNull { raw ->
   runCatching {
    val uri=Uri.parse(raw)
    var name=uri.lastPathSegment ?: "file"
    var size=-1L
    context.contentResolver.query(uri,null,null,null,null)?.use { c ->
     if(c.moveToFirst()) {
      val n=c.getColumnIndex("_display_name")
      val s=c.getColumnIndex("_size")
      if(n>=0) name=c.getString(n) ?: name
      if(s>=0 && !c.isNull(s)) size=c.getLong(s)
     }
    }
    SharedFile(uri,name,size)
   }.getOrNull()
  }
 }
}