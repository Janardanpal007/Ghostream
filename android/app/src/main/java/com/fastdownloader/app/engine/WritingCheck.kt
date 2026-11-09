package com.fastdownloader.app.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Flags stock AI-writing phrases in text. The phrase list is the app's own; matching ignores case, needs whole
 * words, and accepts both straight and curly apostrophes. Keep entries to letters, spaces, hyphens and apostrophes.
 */
object WritingCheck {
    private const val LETTER = "\\p{L}"
    private const val APOSTROPHE = "['’]"

    /** Phrases that read as machine-written when they turn up in ordinary prose. */
    val PHRASES: List<String> = listOf(
        "delve",
        "delves",
        "delving",
        "tapestry",
        "in today's fast-paced",
        "in today's digital age",
        "let that sink in",
        "it's important to note",
        "it is important to note",
        "here's the thing",
        "in the realm of",
        "navigate the complexities",
        "unlock the power",
        "game-changer",
        "seamlessly",
        "in conclusion",
        "a testament to",
        "ever-evolving",
    )

    /** The regular expression both the app and the page use: a whole phrase, any case. */
    val pattern: String =
        "(?<!$LETTER)(" + PHRASES.joinToString("|") { phrase -> phrase.split("'").joinToString(APOSTROPHE) } + ")(?!$LETTER)"

    private val regex = Regex(pattern, RegexOption.IGNORE_CASE)

    /** One phrase found in the text: [start] and [end] are indexes into it, [end] exclusive. */
    data class Hit(val phrase: String, val start: Int, val end: Int)

    fun find(text: String): List<Hit> = regex.findAll(text).map { m ->
        Hit(phrase = m.value, start = m.range.first, end = m.range.last + 1)
    }.toList()

    /**
     * Script for the page: wraps each phrase in a highlighted mark and returns how many were marked. It skips
     * code, form fields and marks it already added, so running it twice doesn't mark anything twice.
     */
    fun script(): String {
        val encoded = Json.encodeToString(pattern)
        return SCRIPT.replace(PATTERN_PLACEHOLDER, encoded)
    }

    private const val PATTERN_PLACEHOLDER = "__PATTERN__"

    private val SCRIPT = """
(function(){try{
var re=new RegExp(__PATTERN__,'giu');
var skip={SCRIPT:1,STYLE:1,NOSCRIPT:1,TEXTAREA:1,INPUT:1,SELECT:1,CODE:1,PRE:1};
var root=document.body||document.documentElement;
var walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null);
var targets=[],node;
while((node=walker.nextNode())){
  var parent=node.parentNode;
  if(!parent||skip[parent.nodeName]||(parent.closest&&parent.closest('.fd-flag')))continue;
  re.lastIndex=0;
  if(re.test(node.nodeValue))targets.push(node);
}
var count=0;
targets.forEach(function(text){
  var source=text.nodeValue,fragment=document.createDocumentFragment(),last=0,m;
  re.lastIndex=0;
  while((m=re.exec(source))){
    fragment.appendChild(document.createTextNode(source.slice(last,m.index)));
    var mark=document.createElement('mark');
    mark.className='fd-flag';
    mark.textContent=m[0];
    mark.style.cssText='background:#ffe066;color:inherit;border-radius:3px;padding:0 2px';
    fragment.appendChild(mark);
    last=m.index+m[0].length;
    count++;
  }
  fragment.appendChild(document.createTextNode(source.slice(last)));
  text.parentNode.replaceChild(fragment,text);
});
return count;
}catch(e){return -1;}})()
""".trimIndent()
}
