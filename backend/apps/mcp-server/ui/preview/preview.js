const products = await (await fetch('/products.json')).json();
const thread = document.getElementById('thread'), frames = new Map(), mutations = new Map();
const actionReferences = new Map();
let items = [], version = 1, count = 0;
const result = (kind, data) => ({ structuredContent: { kind, data } });
const checkedAt = new Date().toISOString();
const merchantName=id=>({'merchant-a':'한강마켓','merchant-b':'노을상점','merchant-c':'별빛몰'})[id]??'한강마켓';
const merchantProduct=(p,id)=>({...p,price:p.price+(id==='merchant-b'?1200:id==='merchant-c'?700:0)});
const offer = p => ({ merchantId: 'merchant-a', merchantName: '한강마켓', merchantProductId: p.id,
  priceAmount: p.price, currency: 'KRW', availability: 'IN_STOCK' });
const product = p => ({ name: p.name, imageUrl: document.getElementById('scenario').value === 'missing' ? null
  : document.getElementById('scenario').value === 'broken' ? 'https://cdn.daisomall.co.kr/preview-missing.jpg' : p.imageUrl,
  brand: '', category: { id: 'drinkware', name: '컵 · 텀블러' }, offers: [
    { ...offer(p), merchantId: 'merchant-b', merchantName: '노을상점', priceAmount: p.price + 1200 },
    offer(p), { ...offer(p), merchantId: 'merchant-c', merchantName: '별빛몰', priceAmount: p.price + 700 }] });
function cart() { const total = items.reduce((n,i)=>n+i.lineTotalAmount,0);return {cartId:'preview-cart',version,items:structuredClone(items),
 merchantSummaries:[...new Set(items.map(i=>i.merchantId))].map(id=>({merchantId:id,merchantName:merchantName(id)})),summary:{itemSubtotalAmount:total,totalAmount:null,discountAmount:null,shippingAmount:null}}; }
function execute(name, args = {}) {
 if(name==='prepare_widget_action'){const reference=crypto.randomUUID();actionReferences.set(reference,structuredClone(args));return result('action.reference',{reference});}
 if(mutations.has(args.idempotencyKey))return mutations.get(args.idempotencyKey);
 let response;
 if(name==='search_products') {
  const mode=document.getElementById('scenario').value;
  return result('catalog.search',{query:{query:args.query??'물컵'},products:mode==='empty'?[]:products.slice(0,1).map(product),filters:{categories:[],merchants:[]},checkedAt,nextCursor:null,failedMerchants:[],
   relatedSearches:mode==='empty'?[]:products.slice(1).map(p=>({query:{query:p.name.includes('텀블러')?'텀블러':'머그컵'},products:mode==='partial'?[]:[product(p)],unavailable:mode==='partial',failedMerchants:[],nextCursor:null}))});
 }
 if(name==='get_cart'||name==='prepare_cart')return result('cart.view',cart());
 if(name==='get_product_detail') {const p=merchantProduct(products.find(p=>p.id===args.merchantProductId)??products[0],args.merchantId);return result('catalog.detail',{
  merchantId:args.merchantId??'merchant-a',merchantName:merchantName(args.merchantId),merchantProductId:p.id,name:p.name,imageUrl:product(p).imageUrl,checkedAt,
  optionGroups:[{id:'type',name:'옵션',values:[{id:'basic',name:'기본 옵션'},{id:'extra',name:'추가 옵션 (QA 예시)'}]}],
  variants:[{merchantVariantId:p.id+'-default',optionValueIds:['basic'],priceAmount:p.price,currency:'KRW',availability:'IN_STOCK',stockQuantity:12,purchasable:true,minQuantity:1,maxQuantity:12},...(document.getElementById('scenario').value==='options'?[{merchantVariantId:p.id+'-extra',optionValueIds:['extra'],priceAmount:p.price+1000,currency:'KRW',availability:'IN_STOCK',stockQuantity:5,purchasable:true,minQuantity:1,maxQuantity:5}]:[])],shipping:{feeAmount:null,status:'UNKNOWN'}});}
 if(name==='add_cart_item') {const p=merchantProduct(products.find(p=>p.id===args.merchantProductId),args.merchantId);items.push({cartItemId:'line-'+(++count),merchantId:args.merchantId,merchantProductId:p.id,merchantVariantId:args.merchantVariantId,name:p.name,imageUrl:p.imageUrl,quantity:args.quantity,lineTotalAmount:p.price*args.quantity,unitPriceAmount:p.price,currency:'KRW',selectedOptions:[{name:'옵션',value:'기본 옵션'}]});version++;response=result('cart.mutation',{outcome:'APPLIED',cart:cart()});}
 if(name==='update_cart_item'){const i=items.find(i=>i.cartItemId===args.cartItemId);i.quantity=args.quantity;i.merchantVariantId=args.merchantVariantId;const base=merchantProduct(products.find(p=>p.id===i.merchantProductId),i.merchantId);i.unitPriceAmount=base.price+(args.merchantVariantId.endsWith('-extra')?1000:0);i.selectedOptions=[{name:'옵션',value:args.merchantVariantId.endsWith('-extra')?'추가 옵션 (QA 예시)':'기본 옵션'}];i.lineTotalAmount=i.unitPriceAmount*i.quantity;version++;response=result('cart.mutation',{outcome:'APPLIED',cart:cart()});}
 if(name==='delete_cart_item'){items=items.filter(i=>i.cartItemId!==args.cartItemId);version++;response=result('cart.mutation',{outcome:'APPLIED',cart:cart()});}
 if(name==='apply_cart_changes'){for(const [index,change] of args.changes.entries()){execute(change.operation==='DELETE'?'delete_cart_item':'update_cart_item',{...change,cartVersion:version,idempotencyKey:args.idempotencyKey+':'+index});}response=result('cart.view',cart());}
 if(name==='prepare_purchase_handoff')response=result('purchase.handoff',{purchaseRequestId:'00000000-0000-4000-8000-000000000001',cartId:'preview-cart',cartVersion:version,status:'PENDING_ADDRESS',expiresAt:new Date(Date.now()+1800000).toISOString()});
 if(name==='get_purchase_status'||name==='resend_purchase_notification')response=result('purchase.status',{purchaseRequestId:args.purchaseRequestId,status:'AWAITING_APPROVAL',merchantCheckouts:[],notifications:[],expiresAt:new Date(Date.now()+1800000).toISOString()});
 if(!response)throw Error('미리보기에서 지원하지 않는 도구');if(args.idempotencyKey)mutations.set(args.idempotencyKey,response);return response;
}
function appendCard(name,args,output,scroll=true){const kind=output.structuredContent.kind;
 const view=kind.startsWith('catalog.search')?'search':kind.startsWith('catalog.detail')?'detail':kind.startsWith('cart.')?'cart':'purchase';
 const caption=document.createElement('p');caption.className='caption';caption.textContent=`COPENHAGEN · ${name} · 새 ${view} 카드`;
 const shell=document.createElement('div');shell.className='card-shell';const frame=document.createElement('iframe');frame.title=`${view} 미리보기`;frame.src='/view/'+view;shell.append(frame);thread.append(caption,shell);frames.set(frame,{output,args});
 if(scroll)shell.scrollIntoView({behavior:'smooth',block:'start'});
}
window.addEventListener('message',event=>{if(event.origin!==location.origin||!event.data.preview)return;
 const entry=[...frames].find(([f])=>f.contentWindow===event.source);if(!entry)return;const [frame,stored]=entry,m=event.data;
 const send=(result,error)=>event.source.postMessage({preview:true,id:m.id,result,error},location.origin);
 try{
 if(m.method==='initialize'){send({});event.source.postMessage({preview:true,method:'input',params:{arguments:stored.args}},location.origin);
  event.source.postMessage({preview:true,method:'theme',params:{theme:document.body.dataset.theme??'light'}},location.origin);
  const mode=document.getElementById('scenario').value;
  setTimeout(()=>event.source.postMessage({preview:true,method:mode==='cancelled'?'cancelled':'result',params:stored.output},location.origin),mode==='slow'?10000:mode==='cancelled'?1500:0);}
 else if(m.method==='size')frame.style.height=Math.ceil(m.params.height+2)+'px';
 else if(m.method==='call')send(execute(m.params.name,m.params.arguments));
 else if(m.method==='context'){stored.context=m.params;send({});}
 else if(m.method==='message'){const text=m.params.content[0].text;const reference=stored.context?.structuredContent?.copenhagenAction?.reference;const intent=actionReferences.get(reference);if(!intent)throw Error('선택 요청을 확인할 수 없어요.');
 const bubble=document.createElement('p');bubble.className='bubble user';bubble.textContent=m.params.content[0].text;thread.append(bubble);
 const output=execute(intent.tool,intent.arguments);appendCard(intent.tool,intent.arguments,output);send({});}
 }catch(e){send(null,e.message);}
});
function reset(){frames.clear();mutations.clear();items=[];version=1;thread.replaceChildren();
 for(const [text,user] of [['물컵 찾아줘',true],['집에서 쓰실 컵인가요, 가지고 다닐 용기인가요? 머그컵이나 텀블러도 함께 찾아볼까요?',false],['집이나 사무실에서 쓸 거야. 3만 원 이하로, 텀블러도 같이 보여줘.',true]]){const p=document.createElement('p');p.className='bubble'+(user?' user':'');p.textContent=text;thread.append(p);}
 appendCard('search_products',{query:'물컵'},execute('search_products'),false);
}
document.querySelectorAll('[data-width]').forEach(b=>b.onclick=()=>{thread.style.maxWidth=b.dataset.width+'px';document.querySelectorAll('[data-width]').forEach(n=>n.classList.toggle('active',n===b));});
document.getElementById('reset').onclick=reset;document.getElementById('scenario').onchange=reset;reset();

document.getElementById('cart-request').onclick=()=>{const p=document.createElement('p');p.className='bubble user';p.textContent='장바구니 보여줘';thread.append(p);appendCard('get_cart',{},execute('get_cart'));};
document.getElementById('theme-toggle').onclick=()=>{
 const theme=document.body.dataset.theme==='dark'?'light':'dark';document.body.dataset.theme=theme;
 document.getElementById('theme-toggle').textContent=theme==='dark'?'라이트모드':'다크모드';
 for(const frame of frames.keys())frame.contentWindow.postMessage({preview:true,method:'theme',params:{theme}},location.origin);
};
