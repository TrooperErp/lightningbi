import { injectGlobalWebcomponentCss } from 'Frontend/generated/jar-resources/theme-util.js';

import { injectGlobalCss } from 'Frontend/generated/jar-resources/theme-util.js';

import $cssFromFile_0 from '@vaadin/vaadin-lumo-styles/lumo.css?inline';
import '@vaadin/common-frontend/ConnectionIndicator.js';
import 'Frontend/generated/jar-resources/ReactRouterOutletElement.tsx';
import 'react-router';
import 'react';

injectGlobalWebcomponentCss($cssFromFile_0.toString());
const loadOnDemand = (key) => {
  const pending = [];
  if (key === '20177c7fbddc3cbb2ad8a9de8f440f79cb12c66b35bf4a53949539ad577e04da') {
    pending.push(import('./chunks/chunk-2a947685b774c8361154e2dc340108a661ec6ce1ffe279ed0cc8ced94e5edcfb.js'));
  }
  if (key === '6200366fed7c7273360acb3003ed785a36114178aa190b855ed43428396b8f51') {
    pending.push(import('./chunks/chunk-ab8eb5a307b0efced8bc7c16b48b6632fb38e7d38d4c48e0238aed2190d4199b.js'));
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