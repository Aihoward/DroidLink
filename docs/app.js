(() => {
  'use strict';
  const OWNER = 'Aihoward';
  const REPO = 'DroidLink';
  const RELEASES_URL = `https://github.com/${OWNER}/${REPO}/releases`;
  const LOCAL_RELEASES_URL = 'releases.json';
  const API_URL = `https://api.github.com/repos/${OWNER}/${REPO}/releases?per_page=30`;
  const state = { releases: [], visible: 8 };
  const $ = (id) => document.getElementById(id);
  const menu = $('site-nav');
  document.querySelector('.menu-toggle').addEventListener('click', (event) => {
    const open = menu.classList.toggle('open');
    event.currentTarget.setAttribute('aria-expanded', String(open));
  });
  menu.addEventListener('click', () => { menu.classList.remove('open'); document.querySelector('.menu-toggle').setAttribute('aria-expanded', 'false'); });

  const formatDate = (value) => new Intl.DateTimeFormat('en-US', { year: 'numeric', month: 'short', day: 'numeric' }).format(new Date(value));
  const formatSize = (bytes) => `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  const apkFor = (release) => {
    const apks = (release.assets || []).filter((asset) => asset.name.toLowerCase().endsWith('.apk'));
    if (!apks.length) return null;
    return apks.find((asset) => /universal|release|stable/i.test(asset.name) && !/test|benchmark/i.test(asset.name)) ||
      apks.find((asset) => !/test|benchmark/i.test(asset.name)) || apks[0];
  };
  const el = (tag, className, text) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  };
  const link = (label, href, className = '') => {
    const node = el('a', className, label);
    node.href = href;
    if (href.startsWith('https://')) { node.target = '_blank'; node.rel = 'noopener noreferrer'; }
    return node;
  };
  const statusBadge = (release, latest = false) => el('span', `badge${release.prerelease ? ' beta' : ''}`, release.prerelease ? 'BETA / EXPERIMENTAL' : (latest ? 'LATEST STABLE' : 'STABLE'));

  function setLatest(release) {
    const apk = apkFor(release);
    $('hero-version').textContent = release.name || release.tag_name;
    $('hero-status').textContent = release.prerelease ? 'Beta' : 'Stable';
    $('hero-date').textContent = formatDate(release.published_at);
    $('hero-size').textContent = apk ? formatSize(apk.size) : 'No APK';
    document.querySelectorAll('.latest-download').forEach((button) => {
      button.textContent = apk ? 'Download latest APK' : 'View latest release';
      button.href = apk ? apk.browser_download_url : release.html_url;
      button.classList.remove('disabled');
      button.removeAttribute('aria-disabled');
    });
    const card = $('latest-release');
    card.classList.remove('skeleton-card');
    card.replaceChildren();
    const info = el('div');
    info.append(statusBadge(release, true), el('h3', '', release.name || release.tag_name));
    const description = el('p', '', apk ? `Official APK: ${apk.name}` : 'This GitHub release does not contain an APK asset.');
    const meta = el('div', 'release-meta');
    meta.append(el('span', '', `Released ${formatDate(release.published_at)}`), el('span', '', apk ? formatSize(apk.size) : 'No APK attached'), el('span', '', release.tag_name));
    info.append(description, meta);
    const actions = el('div', 'hero-actions');
    actions.append(link(apk ? 'Download APK' : 'View release', apk ? apk.browser_download_url : release.html_url, 'button button-primary'), link('Release notes', release.html_url, 'button button-secondary'));
    card.append(info, actions);
  }

  function releaseCard(release) {
    const apk = apkFor(release);
    const card = el('article', 'release-card');
    const head = el('div', 'release-head');
    const info = el('div');
    info.append(statusBadge(release), el('h3', '', release.name || release.tag_name), el('p', '', `${formatDate(release.published_at)} · ${release.tag_name}${apk ? ` · ${apk.name} · ${formatSize(apk.size)}` : ' · No APK attached'}`));
    const actions = el('div', 'release-links');
    if (apk) actions.append(link('Download APK', apk.browser_download_url, 'primary'));
    actions.append(link('View on GitHub', release.html_url));
    head.append(info, actions);
    card.append(head);
    if (release.body) {
      const details = document.createElement('details');
      const summary = el('summary', '', 'Read release notes');
      const notes = el('div', 'release-notes', release.body);
      details.append(summary, notes);
      card.append(details);
    }
    return card;
  }

  function renderReleases() {
    const list = $('release-list');
    list.replaceChildren(...state.releases.slice(0, state.visible).map(releaseCard));
    $('show-more').hidden = state.visible >= state.releases.length;
  }

  function failGracefully(message) {
    document.querySelectorAll('.latest-download').forEach((button) => { button.textContent = 'View GitHub Releases'; button.href = RELEASES_URL; button.classList.remove('disabled'); button.removeAttribute('aria-disabled'); });
    $('hero-version').textContent = 'See GitHub';
    $('latest-release').replaceChildren(el('div', '', message), link('Open GitHub Releases', RELEASES_URL, 'button button-primary'));
    $('release-list').replaceChildren(el('p', 'loading', 'Release history is temporarily unavailable. Open GitHub Releases to continue.'));
  }

  $('show-more').addEventListener('click', () => { state.visible += 8; renderReleases(); });

  // Prefer the live GitHub Releases API so manually published releases appear on the site immediately.
  // Fall back to the bundled releases.json only when the live API is unavailable.
  const loadReleases = () => fetch(API_URL, {
    cache: 'no-store',
    headers: { Accept: 'application/vnd.github+json' }
  })
    .then((response) => {
      if (!response.ok) throw new Error(`GitHub API ${response.status}`);
      return response.json();
    })
    .catch(() => fetch(LOCAL_RELEASES_URL, { cache: 'no-cache' })
      .then((response) => {
        if (!response.ok) throw new Error('Local release data unavailable');
        return response.json();
      }));

  loadReleases()
    .then((releases) => {
      state.releases = releases.filter((release) => !release.draft)
        .sort((a, b) => new Date(b.published_at) - new Date(a.published_at));
      const stable = state.releases.find((release) => !release.prerelease && apkFor(release));
      const beta = state.releases.find((release) => release.prerelease && apkFor(release));
      if (!stable) return failGracefully('No stable GitHub release with an APK is currently available.');
      setLatest(stable);
      if (beta) {
        const box = $('beta-release');
        box.hidden = false;
        box.replaceChildren(el('strong', '', 'Beta / Experimental: '), link(beta.name || beta.tag_name, beta.html_url), document.createTextNode(' — clearly marked for testing.'));
      }
      renderReleases();
    })
    .catch(() => failGracefully('GitHub release information could not be loaded right now.'));
})();