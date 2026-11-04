package com.fastdownloader.app.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ReaderBlock(val t: String, val x: String)

/** A page's article as plain text blocks: headings, paragraphs and code. */
@Serializable
data class ReaderDoc(val title: String, val site: String, val blocks: List<ReaderBlock>)

object Reader {
    /**
     * Finds the article: the element holding most paragraph text (counted on each paragraph's parent, and half
     * on its grandparent), then its headings, paragraphs, list items, quotes and code in page order.
     */
    private const val EXTRACT = """(function(){try{
var score=new Map();
document.querySelectorAll('p').forEach(function(p){var n=(p.innerText||'').trim().length;if(n<60)return;
var a=p.parentElement;if(!a)return;score.set(a,(score.get(a)||0)+n);var b=a.parentElement;if(b)score.set(b,(score.get(b)||0)+n/2);});
var best=null,top=0;score.forEach(function(v,k){if(v>top){top=v;best=k;}});
if(!best||top<400)return '';
var out=[],sel='h1,h2,h3,h4,p,li,blockquote,pre';
best.querySelectorAll(sel).forEach(function(e){
for(var u=e.parentElement;u&&u!==best;u=u.parentElement){if(u.matches(sel))return;}
var cs=getComputedStyle(e);if(cs.display==='none'||cs.visibility==='hidden')return;
var x=(e.innerText||'').trim();if(!x)return;
var tag=e.tagName.toLowerCase();var t=/^h\d/.test(tag)?'h':tag==='pre'?'pre':tag==='li'?'li':tag==='blockquote'?'q':'p';
if(t==='p'&&x.length<25&&!/[.!?]$/.test(x))return;
out.push({t:t,x:x.slice(0,20000)});});
var h=document.querySelector('h1');
return JSON.stringify({title:(h&&h.innerText.trim())||document.title,site:location.hostname.replace(/^www\./,''),blocks:out.slice(0,600)});
}catch(e){return '';}})()"""

    private val json = Json { ignoreUnknownKeys = true }

    fun extract(web: WebView, done: (ReaderDoc?) -> Unit) {
        web.evaluateJavascript(EXTRACT) { result ->
            val doc = runCatching {
                val text = Json.parseToJsonElement(result).jsonPrimitive.content
                if (text.isEmpty()) null else json.decodeFromString(ReaderDoc.serializer(), text)
            }.getOrNull()
            done(doc?.takeIf { it.blocks.size >= 2 })
        }
    }
}

@Composable
fun ReaderView(doc: ReaderDoc, onClose: () -> Unit) {
    val c = colors
    var size by remember { mutableIntStateOf(18) }
    OverlayPage(
        title = doc.site,
        onClose = onClose,
        actions = {
            RoundIcon(Icons.Minus, "Smaller text") { size = (size - 2).coerceAtLeast(12) }
            RoundIcon(Icons.TextSize, "Larger text") { size = (size + 2).coerceAtMost(32) }
        },
    ) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                item {
                    Text(
                        doc.title, style = Type.title.copy(fontSize = (size + 8).sp, lineHeight = (size + 14).sp), color = c.text,
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
                    )
                }
                items(doc.blocks) { b ->
                    val m = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                    when (b.t) {
                        "h" -> Text(b.x, style = Type.heading.copy(fontSize = (size + 3).sp), color = c.text, modifier = m.padding(top = 10.dp))
                        "pre" -> Text(b.x, style = Type.small.copy(fontSize = (size - 4).sp), color = c.text, modifier = m)
                        "q" -> Text(b.x, style = Type.body.copy(fontSize = size.sp, lineHeight = (size * 1.6).sp), color = c.muted, modifier = m.padding(start = 12.dp))
                        "li" -> Text("•  " + b.x, style = Type.body.copy(fontSize = size.sp, lineHeight = (size * 1.6).sp), color = c.text, modifier = m)
                        else -> Text(
                            b.x, style = Type.body.copy(fontFamily = FontFamily.Serif, fontSize = size.sp, lineHeight = (size * 1.65).sp),
                            color = c.text, modifier = m,
                        )
                    }
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}
