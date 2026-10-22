// The sign-in window's sidebar: LodiSave's own page beside the site's login page, saying why
// signing in this way is safe. It reads its site and language from its address and calls nothing.
const q = new URLSearchParams(location.search);
const t = COPY[q.get('lang') === 'tl' ? 'tl' : 'en'];
const site = q.get('name') || t.theSite;
document.documentElement.lang = q.get('lang') === 'tl' ? 'fil' : 'en';
document.getElementById('title').textContent = t.signInTo(site);
document.getElementById('lead').textContent = t.signInLead;
document.getElementById('safe-title').textContent = t.safeTitle;
document.getElementById('safe').append(...t.safe(site, false).map((line) => Object.assign(document.createElement('li'), { textContent: line })));
document.getElementById('use').textContent = t.safeUse(site);
