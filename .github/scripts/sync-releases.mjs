import { writeFile } from 'node:fs/promises';

const owner = 'Aihoward';
const repo = 'DroidLink';
const response = await fetch(`https://api.github.com/repos/${owner}/${repo}/releases?per_page=50`, {
  headers: {
    Accept: 'application/vnd.github+json',
    'User-Agent': 'DroidLink-Pages',
    ...(process.env.GITHUB_TOKEN ? { Authorization: `Bearer ${process.env.GITHUB_TOKEN}` } : {})
  }
});
if (!response.ok) throw new Error(`GitHub Releases request failed: ${response.status}`);
const releases = (await response.json()).filter((release) => !release.draft).map((release) => ({
  name: release.name,
  tag_name: release.tag_name,
  html_url: release.html_url,
  published_at: release.published_at,
  prerelease: release.prerelease,
  body: release.body,
  assets: release.assets.filter((asset) => asset.name.toLowerCase().endsWith('.apk')).map((asset) => ({
    name: asset.name,
    size: asset.size,
    browser_download_url: asset.browser_download_url
  }))
})).sort((a, b) => new Date(b.published_at) - new Date(a.published_at));
await writeFile('docs/releases.json', `${JSON.stringify(releases)}\n`, 'utf8');
console.log(`Prepared ${releases.length} public DroidLink releases.`);
