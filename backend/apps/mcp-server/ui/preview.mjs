import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
process.chdir(dirname(fileURLToPath(import.meta.url)));
const bundle = await build({entryPoints:['src/commerce-app.js'], bundle:true, write:false, format:'iife',
  alias:{'@modelcontextprotocol/ext-apps':resolve('preview/host.js')}});
const css = await readFile('src/commerce.css','utf8');
const fonts = await Promise.all([['400','Regular'],['600','SemiBold']].map(async ([w,n]) => `@font-face{font-family:'IBM Plex Sans KR';font-weight:${w};src:url(data:font/woff2;base64,${(await readFile(`fonts/IBMPlexSansKR-${n}.woff2`)).toString('base64')})}`));
const logo=(await readFile('assets/copenhagen-logo-transparent.png')).toString('base64');
const template = (await readFile('src/commerce.html','utf8')).replace('/* FONTS */',()=>fonts.join(''))
 .replace('/* STYLES */',()=>css).replace('__LOGO__',()=>`data:image/png;base64,${logo}`);
const server=createServer(async(req,res)=>{
 try {const path=new URL(req.url,'http://localhost').pathname;
  if(path==='/products.json'){res.setHeader('Content-Type','application/json');res.end(await readFile('preview/products.json'));return;}
  if(path==='/preview.js'){res.setHeader('Content-Type','text/javascript');res.end(await readFile('preview/preview.js'));return;}
  if(path.startsWith('/view/')){const view=path.split('/')[2];if(!['search','detail','cart','purchase'].includes(view)){res.writeHead(404);res.end();return;}
   res.setHeader('Content-Type','text/html; charset=utf-8');res.end(template.replace('__VIEW__',view).replace('/* SCRIPT */',()=>bundle.outputFiles[0].text.replaceAll('</script','<\\/script')));return;}
  if(path!=='/'){res.writeHead(404);res.end();return;}
  res.setHeader('Content-Type','text/html; charset=utf-8');res.end(await readFile('preview/index.html'));
 }catch{res.writeHead(500);res.end('Preview failed');}
});
server.listen(4320,'127.0.0.1',()=>console.log('Preview: http://127.0.0.1:4320 (fixture only; no commerce API)'));
