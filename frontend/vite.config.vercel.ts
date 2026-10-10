import tailwindcss from "@tailwindcss/vite";
import { nitro } from "nitro/vite";
import vinext from "vinext";
import { defineConfig } from "vite";

// Vercel uses Nitro's serverless output; keep Cloudflare-only bindings in vite.config.ts.
export default defineConfig({
  plugins: [vinext(), tailwindcss(), nitro()],
});
