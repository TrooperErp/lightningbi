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
import '@vaadin/text-area/src/vaadin-text-area.js';
import '@vaadin/combo-box/src/vaadin-combo-box.js';
import 'Frontend/generated/jar-resources/comboBoxConnector.js';
import '@vaadin/component-base/src/debounce.js';
import '@vaadin/component-base/src/async.js';
import '@vaadin/combo-box/src/vaadin-combo-box-placeholder.js';
import '@vaadin/multi-select-combo-box/src/vaadin-multi-select-combo-box.js';
import '@vaadin/radio-group/src/vaadin-radio-group.js';
import '@vaadin/radio-group/src/vaadin-radio-button.js';
import '@vaadin/common-frontend/ConnectionIndicator.js';
import 'Frontend/generated/jar-resources/ReactRouterOutletElement.tsx';
import 'react-router';
import 'react';

const loadOnDemand = (key) => {
  const pending = [];
  if (key === '6200366fed7c7273360acb3003ed785a36114178aa190b855ed43428396b8f51') {
    pending.push(import('./chunks/chunk-12de20dafa9c53f9938fbd40f65022ce8ed819ebf7c63d7ccb124b5ca1464df8.js'));
  }
  if (key === '3d19f3c2f96ca386477942f8125fb203560877b63a15f1f5954766e71d37f38e') {
    pending.push(import('./chunks/chunk-843288ecf7ce026f23d7c34abdd3c3063cf708626d394e21fc14fd6533b83d1a.js'));
  }
  if (key === 'eed83d0542c60e76981627fff57764255b5ac07f9517a4797483a6392b323c79') {
    pending.push(import('./chunks/chunk-a6059dffe0a8be502fa317fbb23fa6c23a5932e128efe3a8da33d82bbf5a9ccb.js'));
  }
  if (key === '7b6aebf56e3723452af3a60bae5a2df68d84d5a4538b3c7269ee56314a1a5ce0') {
    pending.push(import('./chunks/chunk-3fdc4f4160ddff914fd7acdf291b3e4974ad333ec4ea5421676ad80e0fd8e9ea.js'));
  }
  if (key === '20177c7fbddc3cbb2ad8a9de8f440f79cb12c66b35bf4a53949539ad577e04da') {
    pending.push(import('./chunks/chunk-2a947685b774c8361154e2dc340108a661ec6ce1ffe279ed0cc8ced94e5edcfb.js'));
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