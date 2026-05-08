const path = require("path");
const HtmlWebpackPlugin = require("html-webpack-plugin");

module.exports = (env, argv) => {
  const isDev = argv.mode !== "production";
  return {
    entry: "./src/index.tsx",
    devtool: isDev ? "eval-source-map" : "source-map",
    output: {
      path: path.resolve(__dirname, "dist"),
      filename: "bundle.js",
      publicPath: "/",
      clean: true,
    },
    resolve: {
      extensions: [".ts", ".tsx", ".js", ".jsx"],
    },
    module: {
      rules: [
        {
          test: /\.tsx?$/,
          loader: "ts-loader",
          exclude: /node_modules/,
          options: { transpileOnly: true },
        },
        {
          test: /\.css$/,
          use: ["style-loader", "css-loader"],
        },
      ],
    },
    plugins: [
      new HtmlWebpackPlugin({ template: "./public/index.html" }),
    ],
    devServer: {
      port: 8080,
      hot: true,
      historyApiFallback: true,
      // Canva apps run inside an iframe — these headers let the iframe load.
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      },
      static: { directory: path.join(__dirname, "public") },
    },
    performance: { hints: false },
  };
};
