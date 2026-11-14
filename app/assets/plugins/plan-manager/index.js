// V2 adapter for opencode-plan-manager@1.1.11 (MIT, https://github.com/yurihbm/opencode-plan-manager).
// The upstream package only exposes the V1 plugin shape; this wrapper registers its
// four tools through the V2 tool transform, forwards the location directory, and
// translates the V1 `context.ask` approval into the V2 permission API.
import { readFileSync } from "node:fs";
import { OpenPlanManagerPlugin } from "./vendor/opencode-plan-manager.js";

const schemas = JSON.parse(readFileSync(new URL("./schemas.json", import.meta.url), "utf8"));

export default {
  id: "local.plan-manager",
  async setup(ctx) {
    const upstream = await OpenPlanManagerPlugin({
      client: { app: { log: async () => ({}) } },
    });

    await ctx.tool.transform((editor) => {
      for (const [name, upstreamTool] of Object.entries(upstream.tool)) {
        editor.add({
          name,
          description: upstreamTool.description,
          input: schemas[name],
          async execute(input, toolContext) {
            const ask = async ({ permission, patterns, always, metadata }) => {
              await ctx.permission.assert({
                sessionID: toolContext.sessionID,
                agent: toolContext.agent,
                source: { type: "tool", messageID: toolContext.messageID, id: toolContext.id },
                action: permission ?? "edit",
                resources: patterns ?? [],
                save: always ?? [],
                metadata,
              });
            };

            try {
              const result = await upstreamTool.execute(input, {
                ...toolContext,
                directory: ctx.location.directory,
                ask,
              });
              return { content: typeof result === "string" ? result : JSON.stringify(result) };
            } catch (error) {
              const message = error instanceof Error ? error.message : String(error);
              return { content: `<tool-error>\n${name} failed: ${message}\n</tool-error>` };
            }
          },
        });
      }
    });

    return () => {};
  },
};
