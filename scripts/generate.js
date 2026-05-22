const fs = require('fs');
const path = require('path');

const repoRoot = process.cwd();
const pluginsDir = path.join(repoRoot, 'plugins');
const docsDir = path.join(repoRoot, 'docs');
const readmeFile = path.join(repoRoot, 'README.md');
const outputJsonFile = path.join(docsDir, 'index.json');

const START_MARKER = '<!-- PLUGINS-LIST:START -->';
const END_MARKER = '<!-- PLUGINS-LIST:END -->';

const DEFAULT_INFO = {
    name: '未知插件',
    author: '佚名',
    version: '1.0.0',
    updateTime: '19700101',
};

function isValidPlugin(dir) {
    try {
        const files = new Set(fs.readdirSync(dir));
        return ['info.prop', 'main.java', 'readme.md'].every(name => files.has(name));
    } catch {
        return false;
    }
}

function parseInfoProp(file) {
    try {
        const result = { ...DEFAULT_INFO };
        const content = fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '');

        for (const rawLine of content.split(/\r?\n/)) {
            const line = rawLine.trim();
            if (!line || line.startsWith('#') || line.startsWith(';')) continue;

            const i = line.indexOf('=');
            if (i === -1) continue;

            const key = line.slice(0, i).trim();
            const value = line.slice(i + 1).trim();

            if (key in result) result[key] = value;
        }

        return result;
    } catch {
        return { ...DEFAULT_INFO };
    }
}

function normalizeUpdateTime(value) {
    return String(value || '').replace(/\D/g, '').slice(0, 8) || '0';
}

function toSafeFileName(value) {
    return String(value).replace(/[\\/:*?"<>|]+/g, '_').replace(/\s+/g, ' ').trim();
}

function escapeMarkdownTable(value) {
    return String(value ?? '').replace(/\|/g, '\\|').replace(/\r?\n/g, ' ');
}

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function getPluginInfo(pluginPath) {
    const dir = path.relative(repoRoot, pluginPath).replace(/\\/g, '/');
    const { name, author, version, updateTime } = parseInfoProp(path.join(pluginPath, 'info.prop'));
    const homeLink = `https://github.com/xiaobaiweinuli/WAuxiliary_Plugin/tree/main/${dir}`;
    const downloadUrl =
        `https://download-directory.github.io/?url=${encodeURIComponent(homeLink)}` +
        `&filename=${encodeURIComponent(toSafeFileName(`${name}_${version}`))}`;

    return { name, author, version, updateTime, dir, homeLink, downloadUrl };
}

function traversePlugins(dir) {
    return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
        if (!entry.isDirectory()) return [];

        const fullPath = path.join(dir, entry.name);
        return isValidPlugin(fullPath) ? [getPluginInfo(fullPath)] : traversePlugins(fullPath);
    });
}

function generateJSON(plugins) {
    return JSON.stringify({
        generatedAt: new Date().toISOString().split('T')[0],
        totalPlugins: plugins.length,
        plugins: plugins.map(({ name, author, version, updateTime, homeLink, downloadUrl }) => ({
            name,
            author,
            version,
            updateTime,
            homeLink,
            downloadUrl,
        })),
    }, null, 2);
}

function generatePluginsTable(plugins) {
    return plugins.map(plugin => [
        '<details>',
        `  <summary>📌 <b><a href="./${plugin.dir}">${escapeHtml(plugin.name)}</a></b> - ${escapeHtml(plugin.updateTime)} - ${escapeHtml(plugin.version)}</summary>`,
        '',
        `  - 作者：${escapeHtml(plugin.author)} - [下载](${plugin.downloadUrl})`,
        '</details>'
    ].join('\n')).join('\n\n');
}

function updateReadme(file, table) {
    const readme = fs.readFileSync(file, 'utf8');
    const start = readme.indexOf(START_MARKER);
    const end = readme.indexOf(END_MARKER);

    if (start === -1 || end === -1 || start > end) {
        throw new Error('README 中未找到合法的 PLUGINS-LIST 标记区间');
    }

    fs.writeFileSync(
        file,
        `${readme.slice(0, start + START_MARKER.length)}\n\n${table}\n\n${readme.slice(end)}`,
        'utf8'
    );
}

if (!fs.existsSync(pluginsDir)) throw new Error(`插件目录不存在: ${pluginsDir}`);
if (!fs.existsSync(docsDir)) fs.mkdirSync(docsDir, { recursive: true });

const plugins = traversePlugins(pluginsDir).sort(
    (a, b) => parseInt(normalizeUpdateTime(b.updateTime), 10) - parseInt(normalizeUpdateTime(a.updateTime), 10)
);

fs.writeFileSync(outputJsonFile, generateJSON(plugins), 'utf8');
updateReadme(readmeFile, generatePluginsTable(plugins));