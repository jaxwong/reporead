import {build} from 'esbuild';

const outdir = process.argv[2];
if (!outdir) throw new Error('build.mjs requires the generated-assets output directory');

await build({
  entryPoints: ['reader.js'],
  bundle: true,
  format: 'iife',
  minify: true,
  legalComments: 'linked',
  outdir,
});
