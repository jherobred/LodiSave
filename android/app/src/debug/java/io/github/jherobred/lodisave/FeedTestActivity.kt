package io.github.jherobred.lodisave

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Debug builds only: a pretend social feed to test the picker on an emulator, where Threads,
 * Instagram and Reddit aren't installed. Each post's Share opens a sheet whose Copy link copies a
 * real post's link, as those apps do. adb shell am start -n io.github.jherobred.lodisave/.FeedTestActivity
 */
class FeedTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Feed() } }
    }
}

private class Post(val user: String, val link: String, val photos: Int, val video: Boolean = false)

private val posts = listOf(
    Post("nasa", "https://www.threads.com/@nasa/post/Decc755lYhC", 4),
    Post("natgeo", "https://www.threads.com/@natgeo/post/Det8JnuDR_l", 1, video = true),
    Post("BarackObama", "https://x.com/BarackObama/status/266031293945503744", 1),
    Post("pics", "https://www.reddit.com/r/pics/comments/1xa294g/oc_my_3d_butter_squishy_at_a_chalk_art_festival/", 1),
)

@Composable
private fun Feed() {
    val ctx = LocalContext.current
    var sharing by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(posts) { post ->
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(post.user, fontWeight = FontWeight.Bold)
                if (post.photos > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(List(post.photos) { it }) { i -> Picture(i, Modifier.size(250.dp), "Photo ${i + 1} of ${post.photos} by ${post.user}") }
                } else Picture(post.user.length, Modifier.fillMaxWidth().height(280.dp), if (post.video) "Video by ${post.user}" else "Photo by ${post.user}")
                Text("Share", Modifier.clickable { sharing = post.link }.padding(10.dp))
            }
        }
    }
    sharing?.let { link ->
        ModalBottomSheet(onDismissRequest = { sharing = null }) {
            Text("Copy link", Modifier.fillMaxWidth().clickable {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", link))
                sharing = null
            }.padding(24.dp))
        }
    }
}

@Composable
private fun Picture(seed: Int, modifier: Modifier, description: String) {
    val colors = listOf(Color(0xFF3B5BDB), Color(0xFFE8590C), Color(0xFF2F9E44), Color(0xFFAE3EC9), Color(0xFF1098AD))
    Image(ColorPainter(colors[seed % colors.size]), description, modifier)
}
