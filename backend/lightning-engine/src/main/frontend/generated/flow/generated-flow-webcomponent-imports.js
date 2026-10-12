import { injectGlobalWebcomponentCss } from 'Frontend/generated/jar-resources/theme-util.js';

import { injectGlobalCss } from 'Frontend/generated/jar-resources/theme-util.js';

import $cssFromFile_0 from '@vaadin/vaadin-lumo-styles/lumo.css?inline';
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
import 'Frontend/generated/jar-resources/menubarConnector.js';
import '@vaadin/menu-bar/src/vaadin-menu-bar.js';
import '@vaadin/integer-field/src/vaadin-integer-field.js';
import '@vaadin/common-frontend/ConnectionIndicator.js';
import 'Frontend/generated/jar-resources/ReactRouterOutletElement.tsx';
import 'react-router';
import 'react';

injectGlobalWebcomponentCss($cssFromFile_0.toString());
const loadOnDemand = (key) => {
  const pending = [];
  if (key === 'ee08eb804a6cc946f277fd1cc8462ee416acdf906616972f161fd0b17807d7f4') {
    pending.push(import('./chunks/chunk-de1ec46e8713cf5b595df123c2f8807aa54052f9279ec02a9e2bfa0cc29ca4bc.js'));
  }
  if (key === '14e4956518d8cf91138905c07a86af76d84758aee3c69d620283e5b3ebf46370') {
    pending.push(import('./chunks/chunk-f23099b023c00e2acb7f4d19ec53ce594b1916f3950159b4f2db990fc14470b3.js'));
  }
  if (key === '7805f47f1ee82e10ed00a1bf3fbc37d46b7afc8b41c2b36be389ad0d4070de63') {
    pending.push(import('./chunks/chunk-b124c8ebd51fa4f491b9fdf8cfe1b3c903b55682b7febadec508ab2a3dd8c994.js'));
  }
  if (key === 'dbb57de53cdc0bf90d7a335977f77f4fb8bf35a375731d9ef31827ea06256ace') {
    pending.push(import('./chunks/chunk-5447f1d07dbd2890b3340bee8e5e490aeeeb342bf2ba4b2654d51f4630bc1acb.js'));
  }
  if (key === '7b7300bcb775b7d8137a5670cec46deba1c02d3403aae1ba781cc2ce26a3a50f') {
    pending.push(import('./chunks/chunk-1c7d2aed6991e3808e84b14e704e4656093347f41f4ea801bad8232b67a4e03e.js'));
  }
  if (key === '3196fc565da2451df4361282717fefd9636ec27d7d8a3a3091b31ba62d1b7298') {
    pending.push(import('./chunks/chunk-a74a01a1c8c3ca56ae57ba86c80565fdb79911af44433524b057e67a80debcde.js'));
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