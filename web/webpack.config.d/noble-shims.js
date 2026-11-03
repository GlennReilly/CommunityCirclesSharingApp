// Bundle diet (ADR 0035, tools/bundle-analysis): Kotlin/JS loads npm packages with `require`, and webpack must then assume
// every export of an ES module is used, so all of noble's curves, hashes and ciphers would be bundled. For each module we
// write a one-line ES module that re-exports only the names we call and alias the package path to it; the package marks
// itself side-effect free, so webpack drops everything those names do not reach (ristretto, hash-to-curve, blake2s, ...).
// Keep this list in step with the `@file:JsModule` externals in shared/src/jsMain.
if (config.mode === 'production') {
    const fs = require('fs');
    const path = require('path');
    const used = {
        '@noble/curves/ed25519.js': ['ed25519', 'x25519'],
        '@noble/hashes/blake2.js': ['blake2b'],
        '@noble/hashes/argon2.js': ['argon2idAsync'],
        '@noble/ciphers/chacha.js': ['xchacha20poly1305'],
        '@noble/ciphers/salsa.js': ['hsalsa', 'xsalsa20poly1305'],
        '@noble/ciphers/utils.js': ['randomBytes'],
    };
    // A noble module used from Kotlin but missing above would be bundled whole, and a name missing from the list would be
    // undefined at run time in production only; so a new `@file:JsModule("@noble/...")` without an entry stops the build.
    const sources = path.resolve(__dirname, '../../../../shared/src/jsMain');
    const declared = new Set();
    const walk = (dir) => {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) walk(full);
            else if (entry.name.endsWith('.kt')) {
                for (const match of fs.readFileSync(full, 'utf8').matchAll(/@file:JsModule\("(@noble\/[^"]+)"\)/g)) declared.add(match[1]);
            }
        }
    };
    if (fs.existsSync(sources)) walk(sources);
    for (const specifier of declared) {
        if (!(specifier in used)) throw new Error('noble-shims.js: add ' + specifier + ' to the list of used exports');
    }
    const shimDir = path.resolve(__dirname, 'noble-shims');
    fs.mkdirSync(shimDir, { recursive: true });
    config.resolve = config.resolve || {};
    config.resolve.alias = config.resolve.alias || {};
    for (const [specifier, names] of Object.entries(used)) {
        const real = path.resolve(__dirname, '../../node_modules', specifier);
        const shim = path.join(shimDir, specifier.replace(/[^a-z0-9]+/gi, '_') + '.mjs');
        fs.writeFileSync(shim, 'export { ' + names.join(', ') + ' } from ' + JSON.stringify(real) + ';\n');
        config.resolve.alias[specifier + '$'] = shim;
    }
}
