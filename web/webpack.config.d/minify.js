// Stronger minification for the production bundle (the 250 KB gzip budget, ADR 0009): two passes and the options that
// let Terser shorten Kotlin's generated code. The bundle is checked by `:web:checkBundleBudget`.
if (config.mode === 'production') {
    const TerserPlugin = require('minimizer-webpack-plugin');
    config.optimization = config.optimization || {};
    config.optimization.minimize = true;
    config.optimization.minimizer = [
        new TerserPlugin({
            extractComments: false,
            terserOptions: {
                ecma: 2020,
                compress: { passes: 3, pure_getters: true, unsafe_arrows: true, unsafe_methods: true, drop_console: false },
                mangle: { toplevel: false },
                format: { comments: false },
            },
        }),
    ];
}
