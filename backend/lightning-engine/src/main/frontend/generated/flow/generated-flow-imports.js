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
import 'Frontend/generated/jar-resources/dndConnector.js';
import '@vaadin/grid/src/vaadin-grid-tree-toggle.js';
import 'Frontend/generated/jar-resources/treeGridConnector.ts';
import 'Frontend/generated/jar-resources/gridConnector.ts';
import '@vaadin/grid/src/vaadin-grid.js';
import '@vaadin/grid/src/vaadin-grid-column.js';
import '@vaadin/grid/src/vaadin-grid-sorter.js';
import '@vaadin/checkbox/src/vaadin-checkbox.js';
import 'Frontend/generated/jar-resources/vaadin-grid-flow-selection-column.js';
import '@vaadin/grid/src/vaadin-grid-selection-column-base-mixin.js';
import '@vaadin/grid/src/vaadin-grid-column-group.js';
import 'Frontend/generated/jar-resources/lit-renderer.ts';
import 'lit/directives/live.js';
import '@vaadin/context-menu/src/vaadin-context-menu.js';
import 'Frontend/generated/jar-resources/contextMenuConnector.js';
import 'Frontend/generated/jar-resources/contextMenuTargetConnector.js';
import '@vaadin/component-base/src/gestures.js';
import '@vaadin/scroller/src/vaadin-scroller.js';
import '@vaadin/dialog/src/vaadin-dialog.js';
import '@vaadin/progress-bar/src/vaadin-progress-bar.js';
import '@vaadin/combo-box/src/vaadin-combo-box.js';
import 'Frontend/generated/jar-resources/comboBoxConnector.js';
import '@vaadin/component-base/src/debounce.js';
import '@vaadin/component-base/src/async.js';
import '@vaadin/combo-box/src/vaadin-combo-box-placeholder.js';
import '@vaadin/multi-select-combo-box/src/vaadin-multi-select-combo-box.js';
import '@vaadin/common-frontend/ConnectionIndicator.js';
import 'Frontend/generated/jar-resources/ReactRouterOutletElement.tsx';
import 'react-router';
import 'react';

const loadOnDemand = (key) => {
  const pending = [];
  if (key === '20177c7fbddc3cbb2ad8a9de8f440f79cb12c66b35bf4a53949539ad577e04da') {
    pending.push(import('./chunks/chunk-b366c050513479d8060a09311eff2f3f36bce5a07186666d10a8acef4426e733.js'));
  }
  if (key === '48c8b0fb3963386e2867f9e11c9fa03bb83b0030c5bfb20d0067ccd6ec214095') {
    pending.push(import('./chunks/chunk-73d2ec1e60aa79c41633004dd84be10dbeee6d794585abdfcdce4d8e3d101d0d.js'));
  }
  if (key === '7b6aebf56e3723452af3a60bae5a2df68d84d5a4538b3c7269ee56314a1a5ce0') {
    pending.push(import('./chunks/chunk-fb07149275f3bbc5d33481c7fcd605438c0240a973f4f1ce0434f627ec86093a.js'));
  }
  if (key === '6200366fed7c7273360acb3003ed785a36114178aa190b855ed43428396b8f51') {
    pending.push(import('./chunks/chunk-110b6c0bd2341f33e1d9ce95f5a9ca3dc52c15b4301823f0c1de6d58fbb12ea6.js'));
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