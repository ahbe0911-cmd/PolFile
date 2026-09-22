package com.polfile.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private val coral=Color(0xFFFF7776)
private val ink=Color(0xFF1B2B43)
private val soft=Color(0xFFFFF3F2)
private val gray=Color(0xFF728094)

class MainActivity:ComponentActivity() {
 private val pickFiles=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
  files.forEach { runCatching { contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
  if(files.isNotEmpty()) FileStore.add(this,files)
 }
 private val pickFolder=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
  if(uri!=null) {
   runCatching { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    .recoverCatching { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
   FileStore.setFolder(this,uri)
  }
 }
 private val notifyPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
   notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
  setContent {
   var keepOn by remember { mutableStateOf(false) }
   DisposableEffect(keepOn) {
    if(keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
   }
   CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    MaterialTheme(colorScheme=lightColorScheme(primary=coral,surface=Color.White)) {
     Screen(
      keepOn=keepOn,onKeepOn={keepOn=it},
      chooseFiles={pickFiles.launch(arrayOf("*/*"))},
      chooseFolder={pickFolder.launch(null)},
      toggle={running ->
       val intent=Intent(this,ShareService::class.java)
       if(running) stopService(intent) else {
        try { ContextCompat.startForegroundService(this,intent) }
        catch(e:Exception) { Toast.makeText(this,"خطا در اجرای سرور: "+e.localizedMessage,Toast.LENGTH_LONG).show() }
       }
      })
    }
   }
  }
 }
}

@Composable
private fun Screen(keepOn:Boolean,onKeepOn:(Boolean)->Unit,chooseFiles:()->Unit,chooseFolder:()->Unit,toggle:(Boolean)->Unit) {
 val context=LocalContext.current
 var tab by remember {mutableIntStateOf(0)}
 val active by SharingState.running.collectAsState()
 val state by SharingState.message.collectAsState()
 val version by FileStore.revision.collectAsState()
 var ip by remember {mutableStateOf(SharingState.ip(context))}
 LaunchedEffect(Unit) { while(true) {ip=SharingState.ip(context);delay(3000)} }
 val address=if(ip==null) "IP شبکه یافت نشد" else "http://"+ip+":8080"
 Column(Modifier.fillMaxSize().background(Color(0xFFFFF7F5))) {
  Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
   Header()
   Column(Modifier.padding(horizontal=16.dp).offset(y=(-12).dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
    when(tab) {
     0 -> {
      WhiteCard {
       Text("اتصال به ویندوز",color=ink,fontWeight=FontWeight.Bold,fontSize=21.sp)
       Text("آدرس اتصال در شبکه محلی",color=gray,fontSize=12.sp)
       Spacer(Modifier.height(8.dp))
       Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(soft)
        .clickable { copy(context,address,active && ip!=null) }.padding(12.dp),
        verticalAlignment=Alignment.CenterVertically) {
        Text(address,Modifier.weight(1f),textAlign=TextAlign.Center,color=Color(0xFFD54D55),fontSize=17.sp)
        Icon(Icons.Default.ContentCopy,"کپی",tint=coral)
       }
       Spacer(Modifier.height(8.dp))
       Text(if(active) "● سرویس فعال" else "● سرویس غیرفعال",color=if(active) Color(0xFF158C75) else gray)
       Text(state,color=gray,fontSize=12.sp)
       Text("پورت: 8080  •  گوشی و ویندوز باید به یک شبکه متصل باشند.",color=gray,fontSize=11.sp)
      }
      Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
       Button(onClick={toggle(active)},modifier=Modifier.weight(1.15f).height(62.dp),shape=RoundedCornerShape(17.dp),
        colors=ButtonDefaults.buttonColors(containerColor=coral)) {
        Icon(if(active) Icons.Default.Stop else Icons.Default.PlayArrow,null)
        Text(if(active) "توقف اشتراک" else "شروع اشتراک‌گذاری",fontSize=12.sp)
       }
       OutlinedButton(onClick={copy(context,address,active && ip!=null)},modifier=Modifier.weight(1f).height(62.dp),
        shape=RoundedCornerShape(17.dp)) {
        Icon(Icons.Default.ContentCopy,null);Spacer(Modifier.width(4.dp));Text("کپی اتصال",fontSize=12.sp)
       }
      }
      WhiteCard {
       Row(verticalAlignment=Alignment.CenterVertically) {
        Checkbox(checked=keepOn,onCheckedChange=onKeepOn)
        Text("هنگام اشتراک‌گذاری صفحه روشن بماند",fontSize=13.sp,color=ink)
       }
      }
      Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
       ActionTile("افزودن فایل گوشی",Icons.Default.Add,Color(0xFFDCF8EF),{chooseFiles()},Modifier.weight(1f))
       ActionTile("مدیریت فایل‌ها",Icons.Default.Folder,Color(0xFFE6F3FF),{tab=1},Modifier.weight(1f))
       ActionTile("راهنما",Icons.Default.HelpOutline,Color(0xFFF0E9FF),{tab=3},Modifier.weight(1f))
      }
      OutlinedButton(onClick=chooseFolder,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)) {
       Icon(Icons.Default.CreateNewFolder,null);Spacer(Modifier.width(6.dp));Text("افزودن پوشه گوشی")
      }
      Text("اشتراک‌گذاری HTTP بدون رمز عبور است؛ فقط در شبکه خصوصی مورداعتماد استفاده کنید.",color=gray,fontSize=11.sp,textAlign=TextAlign.Center)
     }
     1 -> {
      WhiteCard {
       Text("مدیریت فایل‌ها",fontSize=23.sp,color=ink,fontWeight=FontWeight.Bold)
       Text("فایل‌ها و پوشه‌های انتخاب‌شده برای اشتراک‌گذاری",fontSize=12.sp,color=gray)
       Row {
        TextButton(onClick=chooseFiles){Icon(Icons.Default.Add,null);Text("افزودن فایل")}
        TextButton(onClick=chooseFolder){Icon(Icons.Default.CreateNewFolder,null);Text("پوشه")}
       }
       if(FileStore.hasFolder(context)) TextButton(onClick={FileStore.setFolder(context,null)}){Text("حذف پوشه انتخاب‌شده")}
      }
      val items=remember(version){FileStore.list(context)}
      if(items.isEmpty()) WhiteCard { Text("فایلی انتخاب نشده است.",color=gray) }
      items.forEach { item ->
       WhiteCard {
        Row(verticalAlignment=Alignment.CenterVertically) {
         Icon(Icons.Default.InsertDriveFile,null,tint=coral)
         Spacer(Modifier.width(8.dp))
         Column(Modifier.weight(1f)) {
          Text(item.path.ifBlank {item.name},fontSize=13.sp,color=ink,maxLines=2,overflow=TextOverflow.Ellipsis)
          Text(if(item.size>=0) item.size.toString()+" بایت" else "اندازه نامشخص",fontSize=10.sp,color=gray)
         }
         if(item.path.isEmpty()) IconButton(onClick={FileStore.remove(context,item)}){Icon(Icons.Default.DeleteOutline,"حذف فایل",tint=coral)}
        }
       }
      }
     }
     2 -> WhiteCard {
      Text("پیام‌ها",fontWeight=FontWeight.Bold,fontSize=22.sp,color=ink)
      Text(state,color=gray)
      Text("اتصال: "+address,color=gray)
      Text("برای مشاهده فایل‌ها در ویندوز، همین آدرس را در مرورگر وارد کنید.",color=ink)
     }
     else -> WhiteCard {
      Text("راهنمای پل فایل",fontWeight=FontWeight.Bold,fontSize=22.sp,color=ink)
      Text("۱. گوشی و ویندوز را به یک Wi-Fi مشترک وصل کنید.\n۲. فایل یا پوشه انتخاب کنید.\n۳. روی شروع اشتراک‌گذاری بزنید.\n۴. آدرس IP نمایش‌داده‌شده را در مرورگر ویندوز وارد کنید.\n۵. فایل‌ها را دانلود کنید یا از صفحه مرورگر برای گوشی فایل بفرستید.\n۶. پس از پایان، اشتراک‌گذاری را متوقف کنید.",color=ink,lineHeight=26.sp)
      Text("این سرویس رمز عبور یا TLS ندارد؛ روی شبکه عمومی استفاده نکنید.",color=gray,fontSize=12.sp)
     }
    }
   }
  }
  NavigationBar(containerColor=Color.White,modifier=Modifier.height(73.dp)) {
   listOf(Triple("خانه",Icons.Default.Home,0),Triple("فایل‌ها",Icons.Default.Folder,1),Triple("پیام‌ها",Icons.Default.ChatBubbleOutline,2)).forEach { (label,icon,index) ->
    NavigationBarItem(selected=tab==index,onClick={tab=index},icon={Icon(icon,label)},label={Text(label)},alwaysShowLabel=true,
     colors=NavigationBarItemDefaults.colors(selectedIconColor=coral,selectedTextColor=coral,indicatorColor=soft))
   }
  }
 }
}

private fun copy(context:Context,address:String,allowed:Boolean) {
 if(!allowed){Toast.makeText(context,"ابتدا سرور را راه‌اندازی و گوشی را به شبکه وصل کنید",Toast.LENGTH_LONG).show();return}
 (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("پل فایل",address))
 Toast.makeText(context,"آدرس کپی شد",Toast.LENGTH_SHORT).show()
}

@Composable
private fun WhiteCard(content:@Composable ColumnScope.()->Unit) {
 Surface(shape=RoundedCornerShape(22.dp),color=Color.White,shadowElevation=2.dp,modifier=Modifier.fillMaxWidth()) {
  Column(Modifier.padding(17.dp),verticalArrangement=Arrangement.spacedBy(6.dp),content=content)
 }
}
@Composable
private fun ActionTile(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,tint:Color,onClick:()->Unit,modifier:Modifier=Modifier) {
 Column(modifier.height(125.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).clickable(onClick=onClick).padding(5.dp)
  .clip(RoundedCornerShape(17.dp)).background(tint).padding(8.dp),
  horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
  Icon(icon,null,tint=ink,modifier=Modifier.size(29.dp))
  Spacer(Modifier.height(8.dp))
  Text(label,textAlign=TextAlign.Center,color=ink,fontWeight=FontWeight.Bold,fontSize=12.sp,lineHeight=17.sp)
 }
}
@Composable
private fun Header() {
 Box(Modifier.fillMaxWidth().height(258.dp).background(Brush.linearGradient(listOf(Color(0xFFFFA6A0),coral,Color(0xFFFF8C84))))) {
  Canvas(Modifier.fillMaxSize()) {
   drawCircle(Color.White.copy(alpha=.09f),radius=size.width*.55f,center=Offset(size.width*.10f,0f))
   drawCircle(Color.White.copy(alpha=.10f),radius=size.width*.40f,center=Offset(size.width*.94f,size.height*1.12f))
  }
  Column(Modifier.align(Alignment.TopEnd).padding(22.dp),horizontalAlignment=Alignment.End) {
   Text("پل فایل",fontSize=49.sp,fontWeight=FontWeight.Black,color=Color.White)
   Text("انتقال سریع فایل در شبکه محلی",fontSize=14.sp,color=Color.White)
   Text("ساده  •  سریع  •  بدون اینترنت",fontSize=11.sp,color=ink,modifier=Modifier.padding(top=7.dp)
    .clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha=.38f)).padding(horizontal=10.dp,vertical=5.dp))
  }
  Canvas(Modifier.align(Alignment.BottomStart).padding(start=18.dp,bottom=18.dp).size(width=210.dp,height=145.dp)) {
   val sx=size.width/210f;val sy=size.height/145f
   drawRoundRect(Color(0xFF37455F),Offset(75*sx,35*sy),Size(125*sx,90*sy),androidx.compose.ui.geometry.CornerRadius(8*sx))
   drawRoundRect(Color(0xFFB4CEDA),Offset(82*sx,42*sy),Size(111*sx,74*sy),androidx.compose.ui.geometry.CornerRadius(4*sx))
   drawRoundRect(Color.White,Offset(123*sx,69*sy),Size(38*sx,30*sy),androidx.compose.ui.geometry.CornerRadius(5*sx))
   drawRoundRect(Color(0xFFFF8A83),Offset(132*sx,76*sy),Size(19*sx,14*sy),androidx.compose.ui.geometry.CornerRadius(2*sx))
   drawRoundRect(Color(0xFFEFE7E7),Offset(67*sx,125*sy),Size(141*sx,9*sy),androidx.compose.ui.geometry.CornerRadius(4*sx))
   drawRoundRect(Color(0xFF38465A),Offset(8*sx,35*sy),Size(57*sx,108*sy),androidx.compose.ui.geometry.CornerRadius(10*sx))
   drawRoundRect(Color(0xFFFFD1CD),Offset(13*sx,42*sy),Size(47*sx,86*sy),androidx.compose.ui.geometry.CornerRadius(4*sx))
   drawRoundRect(Color.White,Offset(22*sx,71*sy),Size(29*sx,30*sy),androidx.compose.ui.geometry.CornerRadius(4*sx))
   drawCircle(Color.White,3*sx,Offset(36*sx,136*sy))
   drawArc(Color.White,210f,120f,false,topLeft=Offset(88*sx,3*sy),size=Size(70*sx,50*sy),style=Stroke(width=3*sx))
   drawArc(Color.White,210f,120f,false,topLeft=Offset(100*sx,15*sy),size=Size(46*sx,33*sy),style=Stroke(width=3*sx))
  }
 }
}
