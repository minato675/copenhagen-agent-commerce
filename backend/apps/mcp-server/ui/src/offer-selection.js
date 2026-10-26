export function sortedOffers(offers = []) {
  return [...offers].sort((a, b) => {
    const price = offer => Number.isSafeInteger(offer.priceAmount) && offer.priceAmount >= 0 ? offer.priceAmount : Infinity;
    return price(a) - price(b);
  });
}
