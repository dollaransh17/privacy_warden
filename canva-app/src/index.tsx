import { AppUiProvider } from "@canva/app-ui-kit";
import { createRoot } from "react-dom/client";
import { App } from "./app";

import "@canva/app-ui-kit/styles.css";

const rootEl = document.getElementById("root");
if (!rootEl) {
  throw new Error(
    "Could not find #root element — index.html must contain <div id='root'></div>",
  );
}

createRoot(rootEl).render(
  <AppUiProvider>
    <App />
  </AppUiProvider>,
);
