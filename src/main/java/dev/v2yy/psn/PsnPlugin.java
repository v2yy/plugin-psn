package dev.v2yy.psn;

import dev.v2yy.psn.model.PsnGame;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Component
public class PsnPlugin extends BasePlugin {

    private final SchemeManager schemeManager;
    private final Scheme psnScheme;

    public PsnPlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.psnScheme = Scheme.buildFromType(PsnGame.class);
    }

    @Override
    public void start() {
        // 自定义扩展模型必须在此注册，否则 SchemeNotFoundException（同 PluginLinks 做法）
        schemeManager.register(psnScheme);
    }

    @Override
    public void stop() {
        schemeManager.unregister(psnScheme);
    }
}
