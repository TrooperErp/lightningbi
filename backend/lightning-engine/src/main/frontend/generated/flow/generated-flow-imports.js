import '@vaadin/vertical-layout/src/vaadin-vertical-layout.js';
import '@vaadin/text-field/src/vaadin-text-field.js';
import '@vaadin/tooltip/src/vaadin-tooltip.js';
import '@vaadin/password-field/src/vaadin-password-field.js';
import '@vaadin/button/src/vaadin-button.js';
import 'Frontend/generated/jar-resources/disableOnClickFunctions.js';
import '@vaadin/notification/src/vaadin-notification.js';
import 'Frontend/generated/jar-resources/flow-component-renderer.js';
import 'Frontend/generated/jar-resources/flow-component-directive.js';
import 'lit';
import '@vaadin/icons/vaadin-iconset.js';
import '@vaadin/icon/src/vaadin-icon.js';
import '@vaadin/horizontal-layout/src/vaadin-horizontal-layout.js';
import '@vaadin/dialog/src/vaadin-dialog.js';
import '@vaadin/combo-box/src/vaadin-combo-box.js';
import 'Frontend/generated/jar-resources/comboBoxConnector.js';
import '@vaadin/component-base/src/debounce.js';
import '@vaadin/component-base/src/async.js';
import '@vaadin/combo-box/src/vaadin-combo-box-placeholder.js';
import '@vaadin/multi-select-combo-box/src/vaadin-multi-select-combo-box.js';
import '@vaadin/grid/src/vaadin-grid.js';
import '@vaadin/grid/src/vaadin-grid-column.js';
import '@vaadin/grid/src/vaadin-grid-sorter.js';
import '@vaadin/checkbox/src/vaadin-checkbox.js';
import 'Frontend/generated/jar-resources/gridConnector.ts';
import '@vaadin/grid/src/vaadin-grid-active-item-mixin.js';
import 'Frontend/generated/jar-resources/vaadin-grid-flow-selection-column.js';
import '@vaadin/grid/src/vaadin-grid-column-group.js';
import 'Frontend/generated/jar-resources/lit-renderer.ts';
import 'lit/directives/live.js';
import '@vaadin/context-menu/src/vaadin-context-menu.js';
import 'Frontend/generated/jar-resources/contextMenuConnector.js';
import 'Frontend/generated/jar-resources/contextMenuTargetConnector.js';
import '@vaadin/component-base/src/gestures.js';
import '@vaadin/progress-bar/src/vaadin-progress-bar.js';
import '@vaadin/grid/src/vaadin-grid-tree-toggle.js';
import 'Frontend/generated/jar-resources/treeGridConnector.ts';
import '@vaadin/scroller/src/vaadin-scroller.js';
import '@vaadin/tabs/src/vaadin-tabs.js';
import '@vaadin/tabs/src/vaadin-tab.js';
import '@vaadin/integer-field/src/vaadin-integer-field.js';
import '@vaadin/common-frontend/ConnectionIndicator.js';
import 'Frontend/generated/jar-resources/ReactRouterOutletElement.tsx';
import 'react-router';
import 'react';

const loadOnDemand = (key) => {
  const pending = [];
  if (key === '4a36bab0ffedc3e5126c04a7dd58ae9d680681a844921cc19a442843bbe9dfa4') {
    pending.push(import('./chunks/chunk-b124c8ebd51fa4f491b9fdf8cfe1b3c903b55682b7febadec508ab2a3dd8c994.js'));
  }
  if (key === '6200366fed7c7273360acb3003ed785a36114178aa190b855ed43428396b8f51') {
    pending.push(import('./chunks/chunk-6dede14e28c2ffedd9276fd27150b9880fb4b354d6a2d1e23e0c91900a550b56.js'));
  }
  if (key === '22a7785a08af1f6cdf92f8105f2c0c9eedf441c9782dc20b5bd2c028eb40fe28') {
    pending.push(import('./chunks/chunk-5eadffde7bccaf54b0f776854d7c7714dd8772330c65743e63476e4b05787102.js'));
  }
  if (key === '48c8b0fb3963386e2867f9e11c9fa03bb83b0030c5bfb20d0067ccd6ec214095') {
    pending.push(import('./chunks/chunk-5447f1d07dbd2890b3340bee8e5e490aeeeb342bf2ba4b2654d51f4630bc1acb.js'));
  }
  if (key === 'af026b2d7a6a4b1dfbb55b1ec17a88d1d517d9d1021df9d37ae2d2ea37da8cd8') {
    pending.push(import('./chunks/chunk-d52ab663bbeb5fd29fc9f04e21a1b3309528f1fc43a269c412d754a094af403e.js'));
  }
  if (key === '7b6aebf56e3723452af3a60bae5a2df68d84d5a4538b3c7269ee56314a1a5ce0') {
    pending.push(import('./chunks/chunk-f23099b023c00e2acb7f4d19ec53ce594b1916f3950159b4f2db990fc14470b3.js'));
  }
  return Promise.all(pending);
}

window.Vaadin = window.Vaadin || {};
window.Vaadin.Flow = window.Vaadin.Flow || {};
window.Vaadin.Flow.loadOnDemand = loadOnDemand;
window.Vaadin.Flow.resetFocus = () => {
 let ae=document.activeElement;
 while(ae&&ae.shadowRoot) ae = ae.shadowRoot.activeElement;
 return !ae || ae.blur() || ae.focus() || true;
}