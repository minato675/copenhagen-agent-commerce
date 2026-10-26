export function resultDisposition(result, view) {
  const value = result?.structuredContent ?? result;
  if (result?.isError || value?.error) return 'failed';
  if (view === 'search' && value?.kind === 'catalog.search' && value.data) {
    const data = value.data;
    if (!(data.products?.length || data.relatedSearches?.some(group => group.products?.length)
      || data.searchGroups?.some(group => group.data?.products?.length
        || group.data?.relatedSearches?.some(related => related.products?.length)))) return 'empty';
  }
  return 'render';
}
