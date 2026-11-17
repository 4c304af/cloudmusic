@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.yunting.expressive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class Comment(
    val id: Long,
    val authorId: Long,
    val author: String,
    val avatar: String,
    val content: String,
    val date: String,
    val likes: Int,
    val quote: String?,
)

private data class CommentBatch(
    val recent: List<Comment>,
    val popular: List<Comment>,
    val total: Int,
    val nextOffset: Int?,
)

private data class ReplyBatch(val items: List<Comment>, val total: Int, val nextTime: Long?)

private val commentDate = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
private const val COMMENT_BATCH_SIZE = 20

@Composable
internal fun CommentsPage(kind: String, id: Long, onBack: () -> Unit, onProfile: (Long) -> Unit) {
    var replyParent by rememberSaveable(kind, id) { mutableStateOf<Long?>(null) }
    val back: () -> Unit = {
        if (replyParent == null) onBack() else replyParent = null
    }
    BackHandler(enabled = replyParent != null, onBack = back)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            key(kind, id) {
                var recent by remember { mutableStateOf(emptyList<Comment>()) }
                var popular by remember { mutableStateOf(emptyList<Comment>()) }
                var total by remember { mutableIntStateOf(0) }
                var offset by remember { mutableStateOf<Int?>(0) }
                var loading by remember { mutableStateOf(true) }
                var error by remember { mutableStateOf(false) }
                var request by remember { mutableIntStateOf(0) }
                val listState = rememberLazyListState()

                fun loadMore() {
                    if (loading || offset == null) return
                    loading = true
                    error = false
                    request++
                }

                LaunchedEffect(request) {
                    val start = offset ?: return@LaunchedEffect
                    try {
                        val batch = fetchComments(kind, id, start)
                        if (start == 0) popular = batch.popular
                        recent = (recent + batch.recent).distinctBy(Comment::id)
                        if (start == 0 || batch.total > 0) total = batch.total
                        offset = batch.nextOffset
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        error = true
                    } finally {
                        loading = false
                    }
                }

                val parentId = replyParent
                if (parentId != null) {
                    ReplyList(
                        kind, id, parentId, (popular + recent).firstOrNull { it.id == parentId },
                        back, onProfile,
                    )
                } else {
                    val heading = if (kind == "R_SO_4_") "歌曲评论" else "评论"
                    PageHeading(if (total > 0) "$heading · $total" else heading, onBack)
                    CommentList(
                        recent, popular, loading, error, offset != null, ::loadMore, onProfile,
                        onReplies = { replyParent = it },
                        modifier = Modifier.weight(1f),
                        state = listState,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentList(
    recent: List<Comment>,
    popular: List<Comment>,
    loading: Boolean,
    error: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onProfile: (Long) -> Unit,
    onReplies: (Long) -> Unit,
    modifier: Modifier,
    state: androidx.compose.foundation.lazy.LazyListState,
) {
    val visibleRecent = remember(recent, popular) {
        val shown = popular.mapTo(mutableSetOf(), Comment::id)
        recent.filterNot { it.id in shown }
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (popular.isNotEmpty()) {
            item { Text("热门评论", style = MaterialTheme.typography.titleMedium) }
            items(popular, key = { "popular-${it.id}" }) { CommentCard(it, onProfile, onReplies) }
        }
        if (visibleRecent.isNotEmpty()) {
            item { Text("最新评论", style = MaterialTheme.typography.titleMedium) }
            items(visibleRecent, key = { "recent-${it.id}" }) { CommentCard(it, onProfile, onReplies) }
        }
        item {
            when {
                loading -> LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                error -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("暂时无法加载评论", color = MaterialTheme.colorScheme.error)
                    FilledTonalButton(onClick = onLoadMore) { Text("重试") }
                }
                hasMore -> FilledTonalButton(onClick = onLoadMore) { Text("加载更多") }
                recent.isEmpty() && popular.isEmpty() -> Text("暂无评论")
            }
        }
    }
}

@Composable
private fun ReplyList(
    kind: String,
    id: Long,
    parentId: Long,
    parent: Comment?,
    onBack: () -> Unit,
    onProfile: (Long) -> Unit,
) {
    key(parentId) {
        var replies by remember { mutableStateOf(emptyList<Comment>()) }
        var total by remember { mutableIntStateOf(0) }
        var time by remember { mutableStateOf<Long?>(-1L) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf(false) }
        var request by remember { mutableIntStateOf(0) }

        fun loadMore() {
            if (loading || time == null) return
            loading = true
            error = false
            request++
        }

        LaunchedEffect(request) {
            val cursor = time ?: return@LaunchedEffect
            try {
                val batch = fetchReplies(kind, id, parentId, cursor)
                replies = (replies + batch.items).distinctBy(Comment::id)
                if (cursor == -1L || batch.total > 0) total = batch.total
                time = batch.nextTime
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                error = true
            } finally {
                loading = false
            }
        }

        Column(Modifier.fillMaxSize()) {
            PageHeading(if (total > 0) "回复 · $total" else "回复", onBack)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                parent?.let { item { CommentCard(it, onProfile) } }
                items(replies, key = Comment::id) { CommentCard(it, onProfile) }
                item {
                    when {
                        loading -> LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                        error -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("暂时无法加载回复", color = MaterialTheme.colorScheme.error)
                            FilledTonalButton(onClick = ::loadMore) { Text("重试") }
                        }
                        time != null -> FilledTonalButton(onClick = ::loadMore) { Text("加载更多") }
                        replies.isEmpty() -> Text("暂无回复")
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentCard(comment: Comment, onProfile: (Long) -> Unit, onReplies: ((Long) -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Cover(comment.avatar, Modifier.size(40.dp), radius = 20, emptyIcon = Icons.Rounded.Person)
                if (comment.authorId > 0) {
                    TextButton(onClick = { onProfile(comment.authorId) }, modifier = Modifier.weight(1f)) {
                        Text(comment.author, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(comment.author, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                }
                if (comment.date.isNotBlank()) {
                    Text(comment.date, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            Text(comment.content, style = MaterialTheme.typography.bodyLarge)
            comment.quote?.let { quote ->
                Surface(color = colors.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                    Text(quote, Modifier.fillMaxWidth().padding(8.dp),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            if (comment.likes > 0 || onReplies != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (comment.likes > 0) {
                        Text("${comment.likes} 次赞", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (onReplies != null) {
                        TextButton(onClick = { onReplies(comment.id) }) { Text("查看回复") }
                    }
                }
            }
        }
    }
}

private suspend fun fetchReplies(kind: String, id: Long, parentId: Long, time: Long): ReplyBatch =
    withContext(Dispatchers.IO) {
        require(id > 0 && kind in setOf("R_SO_4_", "R_AL_3_", "A_PL_0_") && parentId > 0)
        val data = Catalog.request(
            "/api/resource/comment/floor/get", "threadId" to "$kind$id", "parentCommentId" to parentId.toString(),
            "time" to time.toString(), "limit" to COMMENT_BATCH_SIZE.toString(),
        ).getJSONObject("data")
        val replies = parseComments(data.optJSONArray("comments"))
        val next = data.optLong("time").takeIf { data.optBoolean("hasMore") && it > 0 && it != time && replies.isNotEmpty() }
        ReplyBatch(replies, data.optInt("totalCount").coerceAtLeast(0), next)
    }

private suspend fun fetchComments(kind: String, id: Long, offset: Int): CommentBatch = withContext(Dispatchers.IO) {
    require(id > 0 && kind in setOf("R_SO_4_", "R_AL_3_", "A_PL_0_"))
    val response = requestComments(kind, id, COMMENT_BATCH_SIZE, offset)
    val rows = response.getJSONArray("comments")
    CommentBatch(
        recent = parseComments(rows),
        popular = if (offset == 0) parseComments(response.optJSONArray("hotComments")) else emptyList(),
        total = response.optInt("total").coerceAtLeast(0),
        nextOffset = if (response.optBoolean("more") && rows.length() > 0) offset + rows.length() else null,
    )
}

internal suspend fun fetchFeaturedSongComment(id: Long): FeaturedSongComment? = withContext(Dispatchers.IO) {
    require(id > 0)
    val hotComments = requestComments("R_SO_4_", id, limit = 1, offset = 0).optJSONArray("hotComments")
    parseComments(hotComments).firstOrNull()?.let { comment ->
        val content = comment.content.lineSequence().filter(String::isNotBlank).joinToString(" ") { it.trim() }
        FeaturedSongComment(comment.author, content)
    }
}

private fun requestComments(kind: String, id: Long, limit: Int, offset: Int) =
    Catalog.request("/api/v1/resource/comments/$kind$id", "limit" to limit.toString(), "offset" to offset.toString())

private fun parseComments(rows: JSONArray?): List<Comment> {
    if (rows == null) return emptyList()
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val id = row.optLong("commentId")
        val content = row.optString("content")
        if (id <= 0 || content.isBlank()) return@mapNotNull null
        val user = row.optJSONObject("user")
        val quote = row.optJSONArray("beReplied")?.optJSONObject(0)?.let { quoted ->
            val body = quoted.optString("content").takeIf { it.isNotBlank() && !quoted.isNull("content") }
            val author = quoted.optJSONObject("user")?.optString("nickname").orEmpty()
            body?.let { if (author.isBlank()) it else "@$author  $it" }
        }
        Comment(
            id = id,
            authorId = user?.optLong("userId") ?: 0L,
            author = user?.optString("nickname").orEmpty().ifBlank { "网易云音乐用户" },
            avatar = Catalog.httpsUri(user?.optString("avatarUrl").orEmpty())?.toString().orEmpty(),
            content = content,
            date = row.optLong("time").takeIf { it > 0 }?.let { commentDate.format(Instant.ofEpochMilli(it)) }.orEmpty(),
            likes = row.optInt("likedCount").coerceAtLeast(0),
            quote = quote,
        )
    }.distinctBy(Comment::id)
}
