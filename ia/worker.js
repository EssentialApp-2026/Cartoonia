/* Cartoonia: motore del regista IA in un worker (WebLLM, Apache 2.0) */
import {WebWorkerMLCEngineHandler} from './web-llm.js';
const handler=new WebWorkerMLCEngineHandler();
self.onmessage=m=>handler.onmessage(m);
