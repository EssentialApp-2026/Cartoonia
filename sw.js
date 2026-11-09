/* Cartoonia: la pagina arriva sempre aggiornata dalla rete quando c'è connessione, dalla cache quando sei offline */
const CACHE='cartoonia-v2-14-1794182581';const FILES=['./','./index.html','./manifest.webmanifest','./icon-192.png','./icon-512.png','./icon-maskable-512.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(FILES.map(u=>new Request(u,{cache:'reload'})))).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{const r=e.request;if(r.method!=='GET'||!r.url.startsWith(self.location.origin))return;
 const p=new URL(r.url).pathname,doc=r.mode==='navigate'||/(\/|index\.html)$/.test(p);
 if(doc){e.respondWith(fetch(r,{cache:'no-cache'}).then(res=>{if(res&&res.ok){const cp=res.clone();caches.open(CACHE).then(c=>c.put('./index.html',cp))}return res}).catch(()=>caches.match('./index.html').then(h=>h||caches.match(r,{ignoreSearch:true}))));return}
 e.respondWith(caches.match(r,{ignoreSearch:true}).then(hit=>hit||fetch(r).then(res=>{if(res&&res.ok){const cp=res.clone();caches.open(CACHE).then(c=>c.put(r,cp))}return res})))});
