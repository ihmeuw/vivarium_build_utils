def call(Map config = [:]) {
  
  def github_author = "{$config.github_author}"
  // Add team members as necessary
  def mapping = [
    "Jim Albright": "albrja",
    "Steve Bachmeier": "sbachmei",
    "Hussain Jafari": "hjafari",
    "Patrick Nast": "pnast",
    "Rajan Mudambi": "rmudambi",
  ]
  return mapping.get(github_author, "channel")
}