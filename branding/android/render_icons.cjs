// Usage: NODE_PATH=<directory containing sharp> node branding/android/render_icons.cjs
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const root = path.resolve(__dirname, '../..');
const out = path.join(root, 'build/branding');
const composite = fs.readFileSync(path.join(out, 'composite.svg'), 'utf8');
const mono = fs.readFileSync(path.join(out, 'ic_launcher_monochrome.svg'), 'utf8');
const shapes = {
  circle: '<circle cx="54" cy="54" r="36"/>',
  squircle: '<path d="M54 18 C84 18 90 24 90 54 C90 84 84 90 54 90 C24 90 18 84 18 54 C18 24 24 18 54 18Z"/>',
  roundedSquare: '<rect x="18" y="18" width="72" height="72" rx="12"/>',
  teardrop: '<path d="M54 18 A36 36 0 0 1 90 54 V90 H54 A36 36 0 0 1 54 18Z"/>'
};
function masked(source, shape) {
  return source.replace('viewBox="0 0 108 108"', 'viewBox="18 18 72 72"')
    .replace('width="108" height="108"', 'width="288" height="288"')
    .replace(/(<svg[^>]*>)/, '$1<defs><clipPath id="mask">'+shape+'</clipPath></defs><g clip-path="url(#mask)">')
    .replace('</svg>', '</g></svg>');
}
async function main() {
  for (const [name, svg] of Object.entries({foreground: fs.readFileSync(path.join(out, 'ic_launcher_foreground.svg')), background: fs.readFileSync(path.join(out, 'ic_launcher_background.svg')), monochrome: Buffer.from(mono)})) {
    const {data, info} = await sharp(svg, {density: 288}).ensureAlpha().raw().toBuffer({resolveWithObject:true});
    let minAlpha=255, maxRadius=0, colored=0;
    for (let y=0;y<info.height;y++) for(let x=0;x<info.width;x++) {
      const i=(y*info.width+x)*4, a=data[i+3]; minAlpha=Math.min(minAlpha,a);
      if(a>127) maxRadius=Math.max(maxRadius, Math.hypot((x+.5)*108/info.width-54,(y+.5)*108/info.height-54));
      if(a>0 && (data[i]!==255 || data[i+1]!==255 || data[i+2]!==255)) colored++;
    }
    console.log(name, {minAlpha, maxRadiusDp:maxRadius, nonWhitePixels:colored});
    if(name==='background' && minAlpha!==255) throw Error('Background is not opaque');
    if(name!=='background' && (minAlpha!==0 || maxRadius>33)) throw Error('Foreground fails safe zone/transparency');
    if(name==='monochrome' && colored!==0) throw Error('Monochrome is not pure white');
  }
  const tiles=[];
  for (const [index,[name,shape]] of Object.entries(shapes).entries()) {
    for(const [row,svg] of [composite,mono].entries()) {
      const themed=row===1 ? svg.replaceAll('#FFFFFF', '#C7E9FF') : svg;
      const image=await sharp(Buffer.from(masked(themed,shape))).resize(192,192).png().toBuffer();
      fs.writeFileSync(path.join(out, `${name}-${row===0?'color':'mono'}.png`),image);
      tiles.push({input:image,left:index*208+8,top:row*208+8});
    }
  }
  for(const [i,size] of [24,32,48,64,96].entries()) {
    tiles.push({input:await sharp(Buffer.from(masked(composite,shapes.circle))).resize(size,size).png().toBuffer(),left:i*152+24,top:440});
  }
  await sharp({create:{width:832,height:552,channels:4,background:'#293546'}}).composite(tiles).png().toFile(path.join(out,'mask-review.png'));
  for(const [density,size] of Object.entries({mdpi:48,hdpi:72,xhdpi:96,xxhdpi:144,xxxhdpi:192})) {
    const dir=path.join(root,'app/src/main/res',`mipmap-${density}`);
    for(const [name,shape] of [['ic_launcher',shapes.roundedSquare],['ic_launcher_round',shapes.circle]]) {
      await sharp(Buffer.from(masked(composite,shape)),{density:288}).resize(size,size).webp({lossless:true}).toFile(path.join(dir,name+'.webp'));
    }
  }
}
main().catch(e=>{console.error(e);process.exitCode=1;});
