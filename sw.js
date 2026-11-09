/* Cartoonia: la pagina arriva sempre aggiornata dalla rete quando c'è connessione, dalla cache quando sei offline.
   Le intestazioni COOP/COEP rendono la pagina "isolata": serve al regista IA sul processore per usare più core. */
const CACHE='cartoonia-v2-23-1794207878';const FILES=['./','./index.html','./manifest.webmanifest','./icon-192.png','./icon-512.png','./icon-maskable-512.png'];
const coi=res=>{if(!res||res.type==='opaque'||res.status===0)return res;const h=new Headers(res.headers);h.set('Cross-Origin-Opener-Policy','same-origin');h.set('Cross-Origin-Embedder-Policy','credentialless');return new Response(res.body,{status:res.status,statusText:res.statusText,headers:h})};
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(FILES.map(u=>new Request(u,{cache:'reload'})))).then(()=>self.skipWaiting()))});
/* cancella solo le vecchie copie dell'app: i modelli del regista IA (cache "webllm") restano */
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>/^cartoonia-/.test(k)&&k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{const r=e.request;if(r.method!=='GET'||!r.url.startsWith(self.location.origin))return;
 const p=new URL(r.url).pathname,doc=r.mode==='navigate'||/(\/|index\.html)$/.test(p);
 if(doc){e.respondWith(fetch(r,{cache:'no-cache'}).then(res=>{if(res&&res.ok){const cp=res.clone();caches.open(CACHE).then(c=>c.put('./index.html',cp))}return coi(res)}).catch(()=>caches.match('./index.html').then(h=>h||caches.match(r,{ignoreSearch:true})).then(coi)));return}
 e.respondWith(caches.match(r,{ignoreSearch:true}).then(hit=>hit||fetch(r).then(res=>{if(res&&res.ok){const cp=res.clone();caches.open(CACHE).then(c=>c.put(r,cp))}return res})))});
