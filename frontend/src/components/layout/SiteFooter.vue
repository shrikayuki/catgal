<template>
  <footer class="site-footer">
    <div class="container site-footer__inner">
      <div>
        <strong>CATGAL</strong>
        <p>为喜欢的故事留下一处清爽、可靠的收藏地。</p>
      </div>
      <p class="site-footer__note">本项目不提供或托管未经授权的商业内容。</p>
    </div>
  </footer>
</template>

<style scoped>
.site-footer {
  border-top: 1px solid var(--color-border);
  background: var(--color-surface);
  padding: var(--space-10) 0;
}

.site-footer__inner {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--space-6);
}

strong { color: var(--color-brand-800); letter-spacing: 0.14em; }
p { margin: 0.5rem 0 0; color: var(--color-text-muted); font-size: var(--text-sm); line-height: 1.7; }
.site-footer__note { max-width: 24rem; text-align: right; }

@media (max-width: 639px) {
  .site-footer__inner { align-items: start; flex-direction: column; }
  .site-footer__note { text-align: left; }
}
</style>
