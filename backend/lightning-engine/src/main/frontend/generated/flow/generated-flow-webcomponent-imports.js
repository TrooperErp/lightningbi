import { injectGlobalWebcomponentCss } from 'Frontend/generated/jar-resources/theme-util.js';

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

const loadOnDemand = (key) => {
  const pending = [];
  if (key === '5eccde012ab3df3e1157a4fa9843b3759c529230285b1129dbba60233fb5a152') {
    pending.push(import('./chunks/chunk-1c7d2aed6991e3808e84b14e704e4656093347f41f4ea801bad8232b67a4e03e.js'));
  }
  if (key === '080703f4ba1404f1f5aa47d3ca6c746d533cd0b39639b080ab49a209bec3c357') {
    pending.push(import('./chunks/chunk-83a0e17b1634bbb2fcc9a19b2d0018f91f45cbd34bdebeedb82d24f5af50de93.js'));
  }
  if (key === 'ff3cfb77135cede2c99a205d73e0c89b12e01b7f1aebcd8b6e1c78924d6eacc4') {
    pending.push(import('./chunks/chunk-5447f1d07dbd2890b3340bee8e5e490aeeeb342bf2ba4b2654d51f4630bc1acb.js'));
  }
  if (key === '5e03fb938bb98c13bf814600406c23b60438fe5809bbb0fea645427a9549e02a') {
    pending.push(import('./chunks/chunk-b124c8ebd51fa4f491b9fdf8cfe1b3c903b55682b7febadec508ab2a3dd8c994.js'));
  }
  if (key === 'af552d47e732a0595519f76b6caa6597d3a85a1579bf516d8bc6dfead8790700') {
    pending.push(import('./chunks/chunk-1e80533d3890df410e47bc4fdb949cd325b2c302eb8a3a3ec77d8cb8d48e6c52.js'));
  }
  if (key === '89e048d437bcab14f5ec1ed479b7d806d73ea11559040cef7f18a17e2e5b529e') {
    pending.push(import('./chunks/chunk-b124c8ebd51fa4f491b9fdf8cfe1b3c903b55682b7febadec508ab2a3dd8c994.js'));
  }
  if (key === 'f76a41bdb8dbc9b33261cc5e110b67bebe9fca78bc402cc5c0f4cdd094c8e89f') {
    pending.push(import('./chunks/chunk-de1ec46e8713cf5b595df123c2f8807aa54052f9279ec02a9e2bfa0cc29ca4bc.js'));
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