// The Claude CLI opens its sign-in page by running $BROWSER <url>. On Android there is no
// browser command, so BROWSER points at our node with this preload: it records the URL for the
// app to open in the phone's browser, then exits. Every other node process ignores it.
const arg = process.argv.slice(1).find((a) => a.includes("https:/") && a.includes("oauth"));
if (arg) {
  const url = arg.slice(arg.indexOf("https:/")).replace(/^https:\/(?!\/)/, "https://");
  require("fs").writeFileSync(process.env.HOME + "/.open-url", url);
  process.exit(0);
}
