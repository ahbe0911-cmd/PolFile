package com.polfile.app

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow

data class SharedFile(val uri:Uri,val name:String,val size:Long,val path:String="")

object FileStore {
 val revision=MutableStateFlow(0)
 private fun prefs(context:Context)=context.getSharedPreferences("shared",0)
 fun add(context:Context,uris:List<Uri>) {
  val set=prefs(context).getStringSet("uris",emptySet()).orEmpty().toMutableSet()
  uris.forEach {set.add(it.toString())}
  prefs(context).edit().putStringSet("uris",set).apply()
  revision.value++
 }
 fun remove(context:Context,file:SharedFile) {
  val set=prefs(context).getStringSet("uris",emptySet()).orEmpty().toMutableSet()
  set.remove(file.uri.toString())
  prefs(context).edit().putStringSet("uris",set).apply()
  revision.value++
 }
 fun setFolder(context:Context,uri:Uri?) {
  prefs(context).edit().putString("folder",uri?.toString()).apply()
  revision.value++
 }
 fun hasFolder(context:Context)=prefs(context).getString("folder",null)!=null
 private fun item(resolver:ContentResolver,uri:Uri,path:String=""):SharedFile?=runCatching {
  var name=uri.lastPathSegment ?: "file"
  var size=-1L
  resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use {c ->
   if(c.moveToFirst()) {
    val n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    val s=c.getColumnIndex(OpenableColumns.SIZE)
    if(n>=0) name=c.getString(n) ?: name
    if(s>=0 && !c.isNull(s)) size=c.getLong(s)
   }
  }
  SharedFile(uri,name,size,path)
 }.getOrNull()
 private fun collect(resolver:ContentResolver,tree:Uri,parent:String,prefix:String,results:MutableList<SharedFile>,depth:Int) {
  if(depth>8 || results.size>=1000) return
  val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
  resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use {c ->
   val dirs=mutableListOf<Pair<String,String>>()
   while(c.moveToNext() && results.size<1000) {
    val id=c.getString(0) ?: continue
    val name=c.getString(1) ?: "file"
    val mime=c.getString(2) ?: ""
    if(mime==DocumentsContract.Document.MIME_TYPE_DIR) dirs.add(id to name)
    else item(resolver,DocumentsContract.buildDocumentUriUsingTree(tree,id),prefix+name)?.let(results::add)
   }
   dirs.forEach { (id,name)->if(results.size<1000) collect(resolver,tree,id,prefix+name+"/",results,depth+1) }
  }
 }
 fun list(context:Context):List<SharedFile> {
  val results=prefs(context).getStringSet("uris",emptySet()).orEmpty()
   .mapNotNull { item(context.contentResolver,Uri.parse(it)) }.toMutableList()
  prefs(context).getString("folder",null)?.let {raw ->
   runCatching {
    val tree=Uri.parse(raw)
    collect(context.contentResolver,tree,DocumentsContract.getTreeDocumentId(tree),"",results,0)
   }
  }
  return results.distinctBy {it.uri.toString()}.take(1000)
 }
}
