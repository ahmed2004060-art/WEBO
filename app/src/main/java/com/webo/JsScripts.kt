package com.webo



// نصوص JavaScript المحقونة داخل الصفحات

internal val HOOK_JS = """(function(){
if(window.__tdlh){return;}
window.__tdlh=1;
var seen={};
function has(){return typeof TDL!=='undefined';}
function rpt(u){
  try{
    if(!u||!has()){return;}
    u=String(u);
    if(u.indexOf('http')!==0){try{u=new URL(u,location.href).href;}catch(e){try{TDL.dg('JsScripts.kt:16 HOOK_JS '+(e&&e.stack||e))}catch(_q){}return;}}
    if(seen[u]){return;}
    seen[u]=1;
    TDL.found(u,location.href);
  }catch(e){try{TDL.dg('JsScripts.kt:20 HOOK_JS '+(e&&e.stack||e))}catch(_q){}}
}
function vtype(ct){
  return ct.indexOf('mpegurl')>=0||ct.indexOf('dash+xml')>=0||ct.indexOf('video/')>=0||(__AUDIO__===1&&ct.indexOf('audio/')>=0);
}
function sniff(u,t){
  try{
    if(!t){return;}
    var s=t.slice(0,2000).replace(/^\uFEFF/,'').trim();
    var k=s.indexOf('<MPD');
    if(s.indexOf('#EXTM3U')===0||(k>=0&&k<400)){rpt(u);}
  }catch(e){try{TDL.dg('JsScripts.kt:31 HOOK_JS '+(e&&e.stack||e))}catch(_q){}}
}
var VRE=/https?:(?:\\?\/){2}[^\s"'<>\\)\]}]+?\.(?:m3u8|mpd|mp4|m4v|mov|webm|mkv|flv|3gp|ogv|wmv|avi|mp3|aac|m4a|opus|flac)(?:\?[^\s"'<>\\)\]}]*)?/gi;
function sc(txt){
  try{
    if(!txt){return;}
    var m=txt.match(VRE),i,u;
    if(m){for(i=0;i<m.length;i++){
      u=m[i].replace(/\\u0026/g,'&').replace(/\\u002F/gi,'/').replace(/\\\//g,'/').replace(/&amp;/g,'&').replace(/\\/g,'');
      rpt(u);
    }}
  }catch(e){try{TDL.dg('JsScripts.kt:42 HOOK_JS '+(e&&e.stack||e))}catch(_q){}}
}
try{
  var ofe=window.fetch;
  if(ofe){window.fetch=function(){
    var a=arguments;
    var p=ofe.apply(this,a);
    try{
      p.then(function(r){
        try{
          var ct=(r.headers.get('content-type')||'').toLowerCase();
          var ru=r.url||(typeof a[0]==='string'?a[0]:((a[0]&&a[0].url)||''));
          if(vtype(ct)){rpt(ru);return;}
          if(/^(image|font|audio)\//.test(ct)||/css|html/.test(ct)){return;}
          var len=parseInt(r.headers.get('content-length')||'0',10);
          if(__STREAM__===0&&/event-stream/.test(ct)){return;}
          var txt=/json|text|javascript|xml/.test(ct);
          if(txt||!len||__READCAP__===0||len<__READCAP__*1024){
            r.clone().text().then(function(t){sniff(ru,t);sc(t);}).catch(function(_e){try{TDL.dg('JsScripts.kt:60 HOOK_JS '+(_e&&_e.stack||_e))}catch(_q){}});
          }
        }catch(e){try{TDL.dg('JsScripts.kt:62 HOOK_JS '+(e&&e.stack||e))}catch(_q){}}
      }).catch(function(_e){try{TDL.dg('JsScripts.kt:63 HOOK_JS '+(_e&&_e.stack||_e))}catch(_q){}});
    }catch(e){try{TDL.dg('JsScripts.kt:64 HOOK_JS '+(e&&e.stack||e))}catch(_q){}}
    return p;
  };}
}catch(e){try{TDL.dg('JsScripts.kt:67 HOOK_JS '+(e&&e.stack||e))}catch(_q){}}
try{
  var xo=XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open=function(m,u){
    var x=this;
    this.addEventListener('readystatechange',function(){
      try{
        if(x.readyState===2){
          var c2=(x.getResponseHeader('content-type')||'').toLowerCase();
          if(vtype(c2)){rpt(x.responseURL||u);}
        }
        if(x.readyState===4&&(x.responseType===''||x.responseType==='text')){
          var ct=(x.getResponseHeader('content-type')||'').toLowerCase();
          if(vtype(ct)||/^(image|font|audio)\//.test(ct)||/css|html/.test(ct)){return;}
          var t=x.responseText;
          if(t){sniff(x.responseURL||u,t);sc(t);}
        }
      }catch(e){try{TDL.dg('JsScripts.kt:84 HOOK_JS '+(e&&e.stack||e))}catch(_q){}}
    });
    return xo.apply(this,arguments);
  };
}catch(e){try{TDL.dg('JsScripts.kt:88 HOOK_JS '+(e&&e.stack||e))}catch(_q){}}
try{
  var d=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');
  if(d&&d.set){
    Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:true,enumerable:d.enumerable,get:d.get,set:function(v){
      try{if(typeof v==='string'&&v.indexOf('http')===0){rpt(v);}}catch(e){try{TDL.dg('JsScripts.kt:93 HOOK_JS '+(e&&e.stack||e))}catch(_q){}}
      return d.set.call(this,v);
    }});
  }
}catch(e){try{TDL.dg('JsScripts.kt:97 HOOK_JS '+(e&&e.stack||e))}catch(_q){}}
try{
  var sa=Element.prototype.setAttribute;
  Element.prototype.setAttribute=function(n,v){
    try{
      if(n==='src'&&typeof v==='string'&&v.indexOf('http')===0){
        var tg=this.tagName;
        if(tg==='VIDEO'||tg==='SOURCE'){rpt(v);}
      }
    }catch(e){try{TDL.dg('JsScripts.kt:106 HOOK_JS '+(e&&e.stack||e))}catch(_q){}}
    return sa.apply(this,arguments);
  };
}catch(e){try{TDL.dg('JsScripts.kt:109 HOOK_JS '+(e&&e.stack||e))}catch(_q){}}
try{
  setInterval(function(){
    var vs=document.querySelectorAll('video'),i;
    for(i=0;i<vs.length;i++){
      var v=vs[i];
      try{
        if(v.preload==='none'){v.preload='metadata';}
        var cs=v.currentSrc||v.src;
        if(cs&&cs.indexOf('http')===0){rpt(cs);}
      }catch(e){try{TDL.dg('JsScripts.kt:119 HOOK_JS '+(e&&e.stack||e))}catch(_q){}}
    }
  },1500);
}catch(e){try{TDL.dg('JsScripts.kt:122 HOOK_JS '+(e&&e.stack||e))}catch(_q){}}
})();
"""

internal val CAPTURE_JS = """(function(){
if(window.__tdlScan){return;}
window.__tdlScan=1;
var RID=window.__tdlRid=(window.__tdlRid||0)+1;
var seen={},cnt=0,steps=0,i,j;
var EXT=/\.(?:m3u8|mpd|mp4|m4v|mov|webm|mkv|flv|3gp|ogv|wmv|avi|mp3|aac|m4a|opus|flac)(?:[?#]|$)/i;
var RE=/https?:(?:\\?\/){2}[^\s"'<>\\)\]}]+?\.(?:m3u8|mpd|mp4|m4v|mov|webm|mkv|flv|3gp|ogv|wmv|avi|mp3|aac|m4a|opus|flac)(?:\?[^\s"'<>\\)\]}]*)?/gi;
function clean(u){return u.replace(/\\u0026/g,'&').replace(/\\u002F/gi,'/').replace(/\\\//g,'/').replace(/&amp;/g,'&').replace(/\\/g,'');}
function rpt(u){
  try{
    if(!u){return;}
    u=String(u);
    if(u.indexOf('http')!==0){try{u=new URL(u,location.href).href;}catch(e){try{TDL.dg('JsScripts.kt:138 CAPTURE_JS '+(e&&e.stack||e))}catch(_q){}return;}}
    if(seen[u]){return;}
    seen[u]=1;cnt++;
    TDL.found(u,location.href);
  }catch(e){try{TDL.dg('JsScripts.kt:142 CAPTURE_JS '+(e&&e.stack||e))}catch(_q){}}
}
function scanText(t){
  try{
    if(!t){return;}
    var m=t.match(RE),k;
    if(m){for(k=0;k<m.length;k++){rpt(clean(m[k]));}}
  }catch(e){try{TDL.dg('JsScripts.kt:149 CAPTURE_JS '+(e&&e.stack||e))}catch(_q){}}
}
function scanDoc(doc){
  try{
    var q=doc.querySelectorAll('video,source,a[href],[data-src],[data-video],[data-url],[data-hls],[data-mp4],[data-stream]');
    var at=['src','href','data-src','data-video','data-url','data-hls','data-mp4','data-stream'];
    for(i=0;i<q.length;i++){
      var el=q[i];
      if(el.tagName==='VIDEO'){
        if(el.currentSrc&&el.currentSrc.indexOf('http')===0){rpt(el.currentSrc);}
        try{if(!el.src&&!el.currentSrc){el.preload='metadata';el.load();}}catch(e){try{TDL.dg('JsScripts.kt:159 CAPTURE_JS '+(e&&e.stack||e))}catch(_q){}}
        if(PLAY&&!el.__tdlp){
          el.__tdlp=1;
          (function(vd){try{var pr=vd.play();if(pr&&pr.then){pr.then(function(){if(PLAYMS>0){setTimeout(function(){try{vd.pause();}catch(e){try{TDL.dg('JsScripts.kt:162 CAPTURE_JS '+(e&&e.stack||e))}catch(_q){}}},PLAYMS);}}).catch(function(_e){try{TDL.dg('JsScripts.kt:162 CAPTURE_JS '+(_e&&_e.stack||_e))}catch(_q){}});}}catch(e){try{TDL.dg('JsScripts.kt:162 CAPTURE_JS '+(e&&e.stack||e))}catch(_q){}}})(el);
        }
      }
      for(j=0;j<at.length;j++){
        var v=el.getAttribute(at[j]);
        if(v&&EXT.test(v)){rpt(v);}
      }
    }
    var ss=doc.querySelectorAll('script:not([src])');
    for(i=0;i<ss.length;i++){
      var tx=ss[i].textContent;
      if(tx){scanText(tx);}
    }
  }catch(e){try{TDL.dg('JsScripts.kt:175 CAPTURE_JS '+(e&&e.stack||e))}catch(_q){}}
}
function scanDom(){
  try{
    scanDoc(document);
    var fr=document.querySelectorAll('iframe');
    for(var f=0;f<fr.length;f++){try{if(fr[f].contentDocument){scanDoc(fr[f].contentDocument);}}catch(e){try{TDL.dg('JsScripts.kt:181 CAPTURE_JS '+(e&&e.stack||e))}catch(_q){}}}
    var es=performance.getEntriesByType('resource');
    for(i=0;i<es.length;i++){
      var n=es[i].name;
      if(EXT.test(n)||/mpegurl|manifest/i.test(n)){rpt(n);}
    }
  }catch(e){try{TDL.dg('JsScripts.kt:187 CAPTURE_JS '+(e&&e.stack||e))}catch(_q){}}
}
function scrollers(){
  var r=[],all=document.querySelectorAll('*'),k;
  for(k=0;k<all.length;k++){
    var e=all[k];
    if(e.scrollHeight>e.clientHeight+80&&e.clientHeight>120){
      var o=getComputedStyle(e).overflowY;
      if(o==='auto'||o==='scroll'||o==='overlay'){r.push(e);}
    }
  }
  r.sort(function(a,b){return b.scrollHeight-a.scrollHeight;});
  return r;
}
var last=-1,still=0,MAXS=__MAXSTEPS__,STILL=__STILL__,PLAY=__PLAY__,PLAYMS=__PLAYMS__,DELAY=__DELAY__;
var sc=scrollers();
function finish(){
  if(window.__tdlRid!==RID){return;}
  try{scanDom();}catch(e){try{TDL.dg('JsScripts.kt:205 CAPTURE_JS '+(e&&e.stack||e))}catch(_q){}}
  try{window.scrollTo(0,0);}catch(e){try{TDL.dg('JsScripts.kt:206 CAPTURE_JS '+(e&&e.stack||e))}catch(_q){}}
  try{TDL.scanDone(cnt,steps);}catch(e){try{TDL.dg('JsScripts.kt:207 CAPTURE_JS '+(e&&e.stack||e))}catch(_q){}}
  window.__tdlScan=0;
}
function tick(){
  if(window.__tdlRid!==RID){return;}
  try{
    steps++;
    scanDom();
    var de=document.scrollingElement||document.documentElement;
    var h=de.scrollHeight,tot=h,k;
    window.scrollBy(0,Math.max(300,window.innerHeight*0.85));
    for(k=0;k<sc.length;k++){tot+=sc[k].scrollHeight;sc[k].scrollTop+=Math.max(300,sc[k].clientHeight*0.85);}
    var atBottom=(window.innerHeight+window.pageYOffset>=de.scrollHeight-4);
    for(k=0;k<sc.length;k++){if(sc[k].scrollTop+sc[k].clientHeight<sc[k].scrollHeight-4){atBottom=false;}}
    tot+=cnt*1000+performance.getEntriesByType('resource').length;
    if(tot===last&&atBottom){still++;}else{still=0;}
    last=tot;
    if(still>=STILL||(MAXS>0&&steps>=MAXS)){setTimeout(finish,DELAY*2);return;}
    setTimeout(tick,DELAY);
  }catch(e){try{TDL.dg('JsScripts.kt:226 CAPTURE_JS '+(e&&e.stack||e))}catch(_q){}finish();}
}
tick();
})();
"""

    // إعادة مسح سريعة لما حمّلته الصفحة (تُستعمل عند الضغط على فيديو blob ولا يوجد رابط ملتقط)
internal val RESCAN_JS = """(function(){
try{
  if(typeof TDL==='undefined'){return;}
  var es=performance.getEntriesByType('resource');
  for(var i=0;i<es.length;i++){
    var n=es[i].name;
    if(/\.(?:m3u8|mpd|mp4|webm|m4v|mov|mkv|flv)(?:[?#]|\b)/i.test(n)||/mpegurl|manifest/i.test(n)){TDL.found(n,location.href);}
  }
}catch(e){try{TDL.dg('JsScripts.kt:241 RESCAN_JS '+(e&&e.stack||e))}catch(_q){}}
})();
"""

internal val OVERLAY_JS = """(function(){
if(window.__tdl){return;}
window.__tdl=1;
var map=new Map();
var MAXN=__MAXN__;
function has(){return typeof TDL!=='undefined';}
var rpt={};
function rep(u,ct){
  try{
    if(!has()||!u){return;}
    ct=(ct||'').toLowerCase();
    if(ct.indexOf('mpegurl')>=0||ct.indexOf('dash+xml')>=0||ct.indexOf('video/')>=0){
      u=String(u);
      if(!rpt[u]){rpt[u]=1;TDL.found(u,location.href);}
    }
  }catch(e){try{TDL.dg('JsScripts.kt:260 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}
}
try{
  var ofe=window.__tdlh?null:window.fetch;
  if(ofe){window.fetch=function(){
    var a=arguments;
    return ofe.apply(this,a).then(function(r){
      try{
        var ct=r.headers.get('content-type')||'';
        rep(r.url||a[0],ct);
        if(/json|text|javascript/i.test(ct)){
          r.clone().text().then(function(t){if(t){sc(t);}}).catch(function(_e){try{TDL.dg('JsScripts.kt:271 OVERLAY_JS '+(_e&&_e.stack||_e))}catch(_q){}});
        }
      }catch(e){try{TDL.dg('JsScripts.kt:273 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}
      return r;
    });
  };}
  var xo=XMLHttpRequest.prototype.open;
  if(!window.__tdlh)XMLHttpRequest.prototype.open=function(m,u){
    var x=this;
    this.addEventListener('readystatechange',function(){
      if(x.readyState===2){try{rep(x.responseURL||u,x.getResponseHeader('content-type'));}catch(e){try{TDL.dg('JsScripts.kt:281 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}}
      if(x.readyState===4){
        try{
          var ct=x.getResponseHeader('content-type')||'';
          if((x.responseType===''||x.responseType==='text')&&/json|text|javascript/i.test(ct)){
            var t=x.responseText;if(t){sc(t);}
          }
        }catch(e){try{TDL.dg('JsScripts.kt:288 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}
      }
    });
    return xo.apply(this,arguments);
  };
}catch(e){try{TDL.dg('JsScripts.kt:293 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}
var VRE=/https?:(?:\\?\/){2}[^\s"'<>\\)\]}]+?\.(?:m3u8|mpd|mp4|m4v|mov|webm|mkv|flv|3gp)(?:\?[^\s"'<>\\)\]}]*)?/gi;
var RRE=/["'](\/[^"'\s<>\\]+?\.(?:m3u8|mpd|mp4|m4v|mov|webm)(?:\?[^"'\s<>\\]*)?)["']/gi;
function sc(txt){
  try{
    if(!txt||!has()){return;}
    var m=txt.match(VRE),i,u;
    if(m){for(i=0;i<m.length;i++){
      u=m[i].replace(/\\u0026/g,'&').replace(/\\u002F/gi,'/').replace(/\\\//g,'/').replace(/&amp;/g,'&').replace(/\\/g,'');
      if(!rpt[u]){rpt[u]=1;TDL.found(u,location.href);}
    }}
    var r2=txt.match(RRE);
    if(r2){for(i=0;i<r2.length&&i<200;i++){
      try{u=new URL(r2[i].slice(1,-1),location.href).href;}catch(e){try{TDL.dg('JsScripts.kt:306 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}continue;}
      if(!rpt[u]){rpt[u]=1;TDL.found(u,location.href);}
    }}
  }catch(e){try{TDL.dg('JsScripts.kt:309 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}
}
var lastLen=-1;
function deep(){
  try{
    var es=performance.getEntriesByType('resource');
    for(var i=0;i<es.length;i++){
      var n=es[i].name,p=n.split('?')[0].toLowerCase();
      if(!rpt[n]&&(es[i].initiatorType==='video'||/\.(m3u8|mpd|mp4|m4v|mov|webm|mkv|flv|3gp)$/.test(p))){
        rpt[n]=1;if(has()){TDL.found(n,location.href);}
      }
    }
  }catch(e){try{TDL.dg('JsScripts.kt:321 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}
  try{
    var html=document.documentElement.innerHTML;
    if(html.length!==lastLen){lastLen=html.length;sc(html);}
  }catch(e){try{TDL.dg('JsScripts.kt:325 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}
}
var POS={fx:0.5,fy:0.04};
try{
  var sp=localStorage.getItem('__tdlpos');
  if(sp){
    var po=JSON.parse(sp);
    if(isFinite(po.fx)&&isFinite(po.fy)){POS.fx=Math.min(1,Math.max(0,po.fx));POS.fy=Math.min(1,Math.max(0,po.fy));}
  }
}catch(e){try{TDL.dg('JsScripts.kt:334 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}
function savePos(){try{localStorage.setItem('__tdlpos',JSON.stringify(POS));}catch(e){try{TDL.dg('JsScripts.kt:335 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}}
var P_LINK='M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z';
var P_DL='M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z';
var P_RT='M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z';
function ico(d){
  var NS='http://www.w3.org/2000/svg';
  var sv=document.createElementNS(NS,'svg');
  sv.setAttribute('viewBox','0 0 24 24');
  sv.setAttribute('width','20');sv.setAttribute('height','20');
  sv.setAttribute('fill','#fff');
  var pa=document.createElementNS(NS,'path');
  pa.setAttribute('d',d);
  sv.appendChild(pa);
  return sv;
}
function mk(d,fn,st){
  var b=document.createElement('div');
  b.appendChild(ico(d));
  b.setAttribute('style','flex:0 0 auto;width:32px;height:32px;display:flex;align-items:center;justify-content:center;background:rgba(255,255,255,.16);border-radius:16px;cursor:pointer;-webkit-user-select:none;user-select:none;');
  b.addEventListener('click',function(e){
    e.stopPropagation();e.preventDefault();
    if(st&&st.moved){st.moved=false;return;}
    fn();
  },true);
  return b;
}
function srcOf(v){
  var s=v.currentSrc||v.src||'';
  if(!s){var so=v.querySelector('source');if(so){s=so.src||'';}}
  return s;
}
function fmt(t){
  if(!isFinite(t)){return '0:00';}
  t=Math.floor(t);
  var m=Math.floor(t/60),s=t%60;
  return m+':'+(s<10?'0':'')+s;
}
function visRect(el){
  var r=el.getBoundingClientRect();
  var l=Math.max(r.left,0),t=Math.max(r.top,0);
  var rr=Math.min(r.right,window.innerWidth),bb=Math.min(r.bottom,window.innerHeight);
  return {l:l,t:t,w:Math.max(rr-l,0),h:Math.max(bb-t,0)};
}
function place(st,el){
  var v=visRect(el);
  var bw=st.box.offsetWidth,bh=st.box.offsetHeight;
  var x=v.l+POS.fx*Math.max(v.w-bw,0);
  var y=v.t+POS.fy*Math.max(v.h-bh,0);
  st.box.style.transform='translate('+Math.round(x)+'px,'+Math.round(y)+'px)';
}
function setSl(st,f){
  st.fil.style.width=(f*100)+'%';
  st.thb.style.left=(f*100)+'%';
}
// أيقونة واحدة لكل فيديو: [نسخ الرابط] [شريط المدة] [تحميل]، وتُسحب بإصبع من أي مكان فيها عدا الشريط
function setup(el){
  var st={box:null,isV:true,ok:false,stalled:false,wt:null,drag:false,dragging:false,moved:false,errSent:false,sl:null,tm:null,rt:null,mid:null,lastTxt:'',lastW:''};
  var box=document.createElement('div');
  box.className='__tdl';
  box.setAttribute('style','position:fixed;left:0;top:0;z-index:2147483647;display:none;flex-direction:row;align-items:center;gap:6px;padding:5px 7px;background:rgba(0,0,0,.74);border-radius:24px;direction:ltr;box-sizing:border-box;will-change:transform;touch-action:none;pointer-events:auto;-webkit-user-select:none;user-select:none;');
  var cp=mk(P_LINK,function(){
    if(!has()){return;}
    TDL.copyVideo(srcOf(el));
  },st);
  var mid=document.createElement('div');
  mid.setAttribute('style','flex:1 1 auto;min-width:0;display:flex;flex-direction:column;align-items:stretch;justify-content:center;');
  var grip=document.createElement('div');
  grip.setAttribute('style','width:30px;height:4px;border-radius:2px;background:rgba(255,255,255,.75);margin:0 auto 3px auto;');
  var sl=document.createElement('div');
  sl.setAttribute('style','position:relative;width:100%;height:20px;margin:0;padding:0;display:none;touch-action:none;');
  var trk=document.createElement('div');
  trk.setAttribute('style','position:absolute;left:0;right:0;top:8px;height:4px;border-radius:2px;background:rgba(255,255,255,.3);');
  var fil=document.createElement('div');
  fil.setAttribute('style','position:absolute;left:0;top:8px;height:4px;width:0;border-radius:2px;background:#5B9BFF;');
  var thb=document.createElement('div');
  thb.setAttribute('style','position:absolute;top:4px;left:0;width:12px;height:12px;margin-left:-6px;border-radius:6px;background:#5B9BFF;box-shadow:0 0 0 2px rgba(0,0,0,.35);');
  sl.appendChild(trk);sl.appendChild(fil);sl.appendChild(thb);
  st.fil=fil;st.thb=thb;st.val=0;
  var tm=document.createElement('div');
  tm.setAttribute('style','font:11px sans-serif;color:#fff;text-align:center;display:none;');
  mid.appendChild(grip);
  mid.appendChild(sl);
  mid.appendChild(tm);
  var rt=mk(P_RT,function(){
    var t=el.currentTime;
    if(el.error||el.networkState===3){
      try{el.load();}catch(e){try{TDL.dg('JsScripts.kt:421 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}
      var f=function(){
        el.removeEventListener('loadedmetadata',f);
        try{el.currentTime=t;}catch(e){try{TDL.dg('JsScripts.kt:424 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}
        try{el.play();}catch(e){try{TDL.dg('JsScripts.kt:425 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}
      };
      el.addEventListener('loadedmetadata',f);
    }else{
      try{el.play();}catch(e){try{TDL.dg('JsScripts.kt:429 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}
    }
    st.stalled=false;st.errSent=false;
  },st);
  rt.style.display='none';
  var dl=mk(P_DL,function(){
    if(!has()){return;}
    TDL.video(srcOf(el),location.href);
  },st);
  box.appendChild(cp);
  box.appendChild(mid);
  box.appendChild(rt);
  box.appendChild(dl);
  st.sl=sl;st.tm=tm;st.rt=rt;st.mid=mid;
  var stop=function(e){e.stopPropagation();};
  ['touchstart','touchmove','touchend','mousedown','mouseup','click'].forEach(function(n){sl.addEventListener(n,stop,false);});
  var seekAt=function(cx){
    var rc=sl.getBoundingClientRect();
    var f=rc.width>0?Math.min(1,Math.max(0,(cx-rc.left)/rc.width)):0;
    st.val=Math.round(f*1000);
    setSl(st,f);
    if(isFinite(el.duration)&&el.duration>0){try{el.currentTime=el.duration*f;}catch(e){try{TDL.dg('JsScripts.kt:450 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}}
  };
  sl.addEventListener('touchstart',function(e){st.drag=true;seekAt(e.touches[0].clientX);e.preventDefault();},{passive:false});
  sl.addEventListener('touchmove',function(e){if(st.drag){seekAt(e.touches[0].clientX);e.preventDefault();}},{passive:false});
  var sEnd=function(){st.drag=false;};
  sl.addEventListener('touchend',sEnd,false);
  sl.addEventListener('touchcancel',sEnd,false);
  var mm=function(e){if(st.drag){seekAt(e.clientX);}};
  sl.addEventListener('mousedown',function(e){st.drag=true;seekAt(e.clientX);
    var up=function(){st.drag=false;document.removeEventListener('mousemove',mm,true);document.removeEventListener('mouseup',up,true);};
    document.addEventListener('mousemove',mm,true);document.addEventListener('mouseup',up,true);});
  // سحب الأيقونة من مكانها (يُحفظ المكان ويُطبَّق على كل الفيديوهات)
  var tx0=0,ty0=0,bx0=0,by0=0,tr=false;
  box.addEventListener('touchstart',function(e){
    if(e.touches.length!==1){return;}
    var t=e.touches[0],rc=box.getBoundingClientRect();
    tx0=t.clientX;ty0=t.clientY;bx0=rc.left;by0=rc.top;tr=true;st.dragging=false;
  },{passive:true});
  box.addEventListener('touchmove',function(e){
    if(!tr){return;}
    var t=e.touches[0],dx=t.clientX-tx0,dy=t.clientY-ty0;
    if(!st.dragging){
      if(Math.abs(dx)+Math.abs(dy)<8){return;}
      st.dragging=true;st.moved=true;
    }
    e.preventDefault();
    var v=visRect(el),bw=box.offsetWidth,bh=box.offsetHeight;
    var rx=Math.max(v.w-bw,1),ry=Math.max(v.h-bh,1);
    POS.fx=Math.min(1,Math.max(0,(bx0+dx-v.l)/rx));
    POS.fy=Math.min(1,Math.max(0,(by0+dy-v.t)/ry));
    place(st,el);
  },{passive:false});
  var endDrag=function(){
    if(!tr){return;}
    tr=false;
    if(st.dragging){
      st.dragging=false;
      savePos();
      setTimeout(function(){st.moved=false;},350);
    }
  };
  box.addEventListener('touchend',endDrag,{passive:true});
  box.addEventListener('touchcancel',endDrag,{passive:true});
  box.addEventListener('click',function(e){e.stopPropagation();},false);
  var clr=function(){if(st.wt){clearTimeout(st.wt);st.wt=null;}st.stalled=false;st.errSent=false;};
  el.addEventListener('waiting',function(){
    if(!st.wt){st.wt=setTimeout(function(){st.stalled=true;st.wt=null;},6000);}
  });
  el.addEventListener('playing',clr);
  el.addEventListener('canplay',clr);
  el.addEventListener('timeupdate',function(){if(st.wt||st.stalled){clr();}});
  el.addEventListener('error',function(){
    st.stalled=true;
    if(!st.errSent&&has()){
      st.errSent=true;
      var c=el.error?el.error.code:0;
      TDL.err('توقف الفيديو (رمز '+c+'): '+srcOf(el).slice(0,150));
    }
  });
  document.body.appendChild(box);
  st.box=box;
  map.set(el,st);
}
// خفيف: يُستدعى أثناء التمرير وكل 400 مللي ثانية، يحرّك الأيقونة مع الفيديو
function upd(){
  var vw=window.innerWidth,vh=window.innerHeight;
  map.forEach(function(st,el){
    if(!st.ok){if(st.box.style.display!=='none'){st.box.style.display='none';}return;}
    var r=el.getBoundingClientRect();
    if(r.width<1||r.bottom<0||r.top>vh||r.right<0||r.left>vw){
      if(st.box.style.display!=='none'){st.box.style.display='none';}
      return;
    }
    if(st.box.style.display!=='flex'){st.box.style.display='flex';}
    var d=el.duration;
    var ok=isFinite(d)&&d>0;
    var dsp=ok?'block':'none';
    if(st.sl.style.display!==dsp){st.sl.style.display=dsp;st.tm.style.display=dsp;}
    var w=ok?Math.max(170,Math.min(r.width-12,300))+'px':'';
    if(w!==st.lastW){st.lastW=w;st.box.style.width=w;}
    if(ok){
      if(!st.drag){setSl(st,Math.min(1,Math.max(0,el.currentTime/d)));}
      var tx=fmt(el.currentTime)+' / '+fmt(d);
      if(tx!==st.lastTxt){st.lastTxt=tx;st.tm.textContent=tx;}
    }
    var rs=(st.stalled||el.error)?'block':'none';
    if(st.rt.style.display!==rs){st.rt.style.display=rs;}
    if(!st.dragging){place(st,el);}
  });
}
function scan(){
  var list=document.querySelectorAll('video');
  for(var j=0;j<list.length;j++){
    var cs0=list[j].currentSrc||list[j].src||'';
    if(cs0.indexOf('http')===0&&!rpt[cs0]&&has()){rpt[cs0]=1;try{TDL.found(cs0,location.href);}catch(e){try{TDL.dg('JsScripts.kt:544 OVERLAY_JS '+(e&&e.stack||e))}catch(_q){}}}
  }
  for(var i=0;i<list.length;i++){
    var el=list[i];
    if(!map.has(el)&&(MAXN<=0||map.size<MAXN)){
      if(el.tagName==='VIDEO'){setup(el);}
    }
  }
  map.forEach(function(st,el){
    if(!document.contains(el)){
      if(st.box&&st.box.parentNode){st.box.parentNode.removeChild(st.box);}
      map.delete(el);
    }else{
      var r2=el.getBoundingClientRect();
      var cs=window.getComputedStyle(el);
      var big=st.isV?(r2.width>=80&&r2.height>=60):(r2.width>=100&&r2.height>=100);
      st.ok=big&&cs.display!=='none'&&cs.visibility!=='hidden';
    }
  });
  upd();
}
var tick=false;
function sched(){
  if(tick){return;}
  tick=true;
  requestAnimationFrame(function(){tick=false;upd();});
}
window.addEventListener('scroll',sched,{capture:true,passive:true});
window.addEventListener('touchmove',sched,{capture:true,passive:true});
window.addEventListener('resize',sched,false);
setInterval(function(){if(!document.hidden){upd();}},400);
setInterval(function(){if(!document.hidden){scan();}},1200);
setInterval(function(){if(!document.hidden){deep();}},2500);
scan();
deep();
})();
"""
