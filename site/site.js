const tabs = [...document.querySelectorAll('[role="tab"]')];

function selectTab(tab) {
  for (const item of tabs) {
    const selected = item === tab;
    item.setAttribute('aria-selected', String(selected));
    item.tabIndex = selected ? 0 : -1;
    document.getElementById(item.getAttribute('aria-controls')).hidden = !selected;
  }
}

for (const [index, tab] of tabs.entries()) {
  tab.addEventListener('click', () => selectTab(tab));
  tab.addEventListener('keydown', (event) => {
    let target;
    if (event.key === 'ArrowRight') target = tabs[(index + 1) % tabs.length];
    if (event.key === 'ArrowLeft') target = tabs[(index - 1 + tabs.length) % tabs.length];
    if (event.key === 'Home') target = tabs[0];
    if (event.key === 'End') target = tabs.at(-1);
    if (target) {
      event.preventDefault();
      selectTab(target);
      target.focus();
    }
  });
}

if (navigator.clipboard && window.isSecureContext) {
  for (const button of document.querySelectorAll('[data-copy]')) {
    button.hidden = false;
    button.addEventListener('click', async () => {
      const status = document.getElementById('copy-status');
      button.disabled = true;
      try {
        await navigator.clipboard.writeText(document.getElementById(button.dataset.copy).textContent);
        status.textContent = 'Commands copied. Ready for your terminal.';
        button.textContent = 'Copied!';
      } catch {
        status.textContent = 'Select the commands above and copy them manually.';
      } finally {
        button.disabled = false;
      }
    });
  }
}
