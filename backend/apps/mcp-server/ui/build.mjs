import { build } from 'esbuild';
import { readFile, writeFile } from 'node:fs/promises';

const license = (await readFile('fonts/LICENSE.txt', 'utf8')).replaceAll('\r\n', '\n')
  .split('\n').map(line => line.trimEnd()).join('\n');
const fonts = [`/* IBM Plex: https://github.com/IBM/plex\n${license.replaceAll('*/', '* /')} */`];
for (const [weight, name] of [[400, 'Regular'], [600, 'SemiBold']]) {
  const bytes = await readFile(`fonts/IBMPlexSansKR-${name}.woff2`);
  fonts.push(`@font-face{font-family:'IBM Plex Sans KR';font-style:normal;font-weight:${weight};font-display:swap;src:url(data:font/woff2;base64,${bytes.toString('base64')}) format('woff2')}`);
}
const logo = (await readFile('assets/copenhagen-logo-transparent.png')).toString('base64');
const catalogStyles = (await readFile('src/catalog.css', 'utf8')).replaceAll('\r\n', '\n');
for (const [entry, template, destination] of [['app', 'index', 'home'], ['catalog-app', 'catalog', 'catalog'], ['integration-app', 'catalog', 'integration']]) {
const bundle = await build({ entryPoints: [`src/${entry}.js`], bundle: true, minify: true,
  platform: 'browser', format: 'iife', target: ['es2022'], write: false, legalComments: 'eof' });
const script = bundle.outputFiles[0].text.replaceAll('</script', '<\\/script');
let html = (await readFile(`src/${template}.html`, 'utf8')).replaceAll('\r\n', '\n')
  .replace('/* FONTS */', () => fonts.join('\n'))
  .replace('/* STYLES */', () => template === 'catalog' ? catalogStyles : '')
  .replace('/* SCRIPT */', () => script)
  .replace('__LOGO__', () => 'data:image/png;base64,' + logo);
if (destination === 'integration') html = html.replace(/      <details><summary>앱이 열리지 않나요\?[\s\S]*?<\/label>\n/, '')
  .replace(' data-handoff-link-base="__HANDOFF_LINK_BASE__"', '')
  .replace('<button id="handoff-open" hidden>앱 열기</button>', '');
const output = `../src/main/resources/mcp-ui/${destination}.html`;
if (process.argv.includes('--check')) {
  if ((await readFile(output, 'utf8')).replaceAll('\r\n', '\n') !== html) throw new Error('UI bundle is stale. Run npm run build.');
  console.log('UI bundle matches source.');
} else {
  await writeFile(output, html);
  console.log(`Built ${destination}.html (${Buffer.byteLength(html)} bytes).`);
}
}

const commerceStyles = (await readFile('src/commerce.css', 'utf8')).replaceAll('\r\n', '\n');
for (const view of ['search', 'detail', 'cart', 'purchase']) {
  const bundle = await build({ entryPoints: ['src/commerce-app.js'], bundle: true, minify: true,
    platform: 'browser', format: 'iife', target: ['es2022'], write: false, legalComments: 'eof' });
  const html = (await readFile('src/commerce.html', 'utf8')).replaceAll('\r\n', '\n')
    .replace('/* FONTS */', () => fonts.join('\n'))
    .replace('/* STYLES */', () => commerceStyles)
    .replace('__VIEW__', view).replace('__LOGO__', () => 'data:image/png;base64,' + logo)
    .replace('/* SCRIPT */', () => bundle.outputFiles[0].text.replaceAll('</script', '<\\/script'));
  const output = `../src/main/resources/mcp-ui/commerce-${view}.html`;
  if (process.argv.includes('--check')) {
    if ((await readFile(output, 'utf8')).replaceAll('\r\n', '\n') !== html) throw new Error(`Stale ${view} UI`);
    console.log(`Commerce ${view} bundle matches source.`);
  } else { await writeFile(output, html); console.log(`Built commerce-${view}.html`); }
}
